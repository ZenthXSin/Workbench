package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.shell.AgentShell
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 首次启动自动准备环境：下载并解压 Alpine → 跑初始化脚本（软件源、基础软件、MCP、Codex）→ 连接 MCP。
 * 状态（当前步骤、估算进度）给首次启动页和设置页显示；中途退出下次会接着装。
 */
class LinuxProvisioner(
    private val env: LinuxEnvironment,
    private val shell: AgentShell,
    private val scope: CoroutineScope,
    private val diagnosticsDir: File = env.toHost("/var/log/rustwright"),
    private val onBusy: (Boolean) -> Unit = {},
    private val addons: () -> List<io.github.zenthxsin.rustwright.core.shell.EnvironmentAddon> = { emptyList() },
) {
    enum class Step(val label: String, val end: Float) {
        DOWNLOAD("下载 Alpine Linux", 0.15f),
        EXTRACT("解压系统", 0.30f),
        PACKAGES("安装 git、node、python", 0.65f),
        AGENT("安装助手与扩展", 0.90f),
        CONNECT("连接工具服务", 1.0f),
    }

    private val setup = LinuxSetup(env, shell, addons)

    @Volatile var step: Step? = null; private set
    /** 当前步骤内的说明（如已下载多少 MB）。 */
    @Volatile var detail: String = ""; private set
    @Volatile var progress: Float = 0f; private set
    @Volatile var error: String? = null; private set
    @Volatile private var job: Job? = null
    @Volatile private var lastReport = ""

    /** Prepared on the worker thread so the failure button never reads large logs on the UI thread. */
    fun failureReport(): String = lastReport.ifEmpty { error.orEmpty() }

    /** 环境已经完全就绪（不需要首次启动页）。 */
    val ready: Boolean get() = !env.managed || (env.installed && setup.done)
    // Cancellation can still be stopping the watcher and releasing background work.
    // Keep the UI busy until cleanup finishes, matching start()'s duplicate-run guard.
    val running: Boolean get() = job?.isCompleted == false

    /** Java 默认不装（约 300 MB）；要用时装 Java 21（含 javac），过程显示在助手的终端会话里。 */
    suspend fun installJava(): Boolean {
        val r = shell.run("apk add --no-cache openjdk21-jdk && java -version && javac -version", title = "安装 Java 21", timeoutMillis = 30 * 60_000L)
        return r.exitCode == 0 && !r.timedOut
    }

    /** 修复：去掉完成标记后重跑初始化脚本（脚本本身可重入，会补装缺的、修坏依赖，保留已有配置和项目）。 */
    @Synchronized fun repair() {
        if (job?.isCompleted == false) return
        runCatching { env.toHost(LinuxSetup.MARKER).delete() }
        start()
    }

    /** 开始（或接着）准备环境；已经在跑时不重复启动。 */
    @Synchronized fun start() {
        if (ready || job?.isCompleted == false) return
        error = null
        lastReport = ""
        progress = 0f
        detail = ""
        job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val download = StringBuilder()
            var setupStarted = false
            try {
                onBusy(true)
                diagnosticsDir.mkdirs()
                if (!env.installed) {
                    set(Step.DOWNLOAD, 0f)
                    env.install { line ->
                        download.appendLine(line)
                        if (download.length > 16 * 1024) download.delete(0, download.length - 16 * 1024)
                        when {
                            line.startsWith("解压") -> set(Step.EXTRACT, Step.DOWNLOAD.end)
                            line.startsWith("下载") -> {
                                detail = Regex("([0-9]+) MB").find(line)?.let { it.groupValues[1] + " MB" } ?: ""
                                // 约 30 MB 的压缩包：按已下载量在本步内推进
                                val mb = Regex("([0-9]+) MB").find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                                progress = (Step.DOWNLOAD.end * (mb / 4f)).coerceAtMost(Step.DOWNLOAD.end * 0.98f)
                            }
                            line.startsWith("完成") -> set(Step.EXTRACT, Step.EXTRACT.end * 0.99f)
                        }
                    }
                }
                runCatching { File(diagnosticsDir, "download.log").writeText(download.toString()) }
                set(Step.PACKAGES, Step.EXTRACT.end)
                setupStarted = true
                env.toHost("/tmp/rw-setup-step").delete()
                val watcher = launch { watchSetup() }
                try {
                    setup.run()
                } finally {
                    watcher.cancelAndJoin()
                }
                set(Step.CONNECT, 1f)
                step = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val failedStage = (failure as? LinuxSetupFailure)?.stage ?: step?.label.orEmpty()
                if (failedStage.isNotBlank()) detail = failedStage
                lastReport = buildString {
                    appendLine("Rustwright 环境安装失败")
                    appendLine("系统：${env.distro}（${System.getProperty("os.arch")}）")
                    appendLine("阶段：$failedStage")
                    appendLine("时间：${java.time.Instant.now()}")
                    appendLine()
                    appendLine("失败原因：")
                    appendLine(failure.message ?: failure.javaClass.simpleName)
                    if (download.isNotEmpty()) appendLine("\n下载记录：\n$download")
                    if (setupStarted) {
                        listOf(
                            "/var/log/rustwright/setup.log" to 128 * 1024,
                            "/opt/rw-modding-mcp/bootstrap.log" to 32 * 1024,
                        ).forEach { (path, limit) ->
                            val log = SetupDiagnostics.tail(env.toHost(path), limit)
                            if (log.isNotEmpty()) appendLine("\n本地保留日志（可能包含上次记录） $path：\n$log")
                        }
                    }
                    appendLine("\n技术调用栈（排查时使用）：")
                    appendLine(failure.stackTraceToString().take(16 * 1024))
                }
                runCatching {
                    diagnosticsDir.mkdirs()
                    File(diagnosticsDir, "download.log").writeText(download.toString())
                    File(diagnosticsDir, "failure.txt").writeText(lastReport)
                }
                error = SetupDiagnostics.summary(failure.message ?: failure.toString())
            } finally {
                runCatching { onBusy(false) }
            }
        }
        job?.start()
    }

    private fun set(s: Step, p: Float) {
        step = s
        progress = p
        detail = ""
    }

    /** 初始化脚本每一步写 /tmp/rw-setup-step，这里据此推进进度；同一步里随时间缓慢前进。 */
    private suspend fun watchSetup() = withContext(Dispatchers.IO) {
        var last = ""
        var since = System.currentTimeMillis()
        while (isActive) {
            val name = runCatching { env.toHost("/tmp/rw-setup-step").readText().trim() }.getOrDefault("")
            if (name != last) {
                last = name
                since = System.currentTimeMillis()
                detail = name
            }
            val (s, start) = when {
                name.contains("Codex") || name.contains("扩展") -> Step.AGENT to Step.PACKAGES.end
                name.contains("MCP") || name.contains("rw-modding") -> Step.PACKAGES to (Step.EXTRACT.end + Step.PACKAGES.end) / 2f
                name.contains("完成") -> Step.CONNECT to Step.AGENT.end
                else -> Step.PACKAGES to Step.EXTRACT.end
            }
            step = s
            val span = s.end - start
            // 指数逼近本步终点，不会超过
            val t = (System.currentTimeMillis() - since) / 1000f
            progress = maxOf(progress, start + span * (1f - kotlin.math.exp(-t / 90f)) * 0.95f)
            delay(500)
        }
    }
}
