package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.agent.McpServerSpec
import io.github.zenthxsin.rustwright.core.shell.AgentShell
import io.github.zenthxsin.rustwright.core.shell.CommandResult
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class LinuxSetupTest {
    @TempDir lateinit var root: File

    @Test fun bundledSetupScriptUsesUnixLineEndings() {
        assertFalse('\r' in LinuxSetup.SCRIPT, "Bash must not receive CRLF from packaged resources")
    }

    @Test fun optionalAddonHasItsOwnReadinessMarkerAndRunsInsideTheSetupScript() {
        val env = Env(root)
        env.toHost(LinuxSetup.MARKER).apply { parentFile.mkdirs(); writeText("ready") }
        val addon = io.github.zenthxsin.rustwright.core.shell.EnvironmentAddon("sample", "/etc/workbench/sample.ready", { "echo sample-addon" })
        val setup = LinuxSetup(env, Shell { error("not invoked") }, { listOf(addon) })
        assertFalse(setup.done)
        val script = setup.script()
        assertTrue("echo sample-addon" in script)
        assertTrue(script.indexOf("echo sample-addon") < script.indexOf("step \"清理缓存\""))
        assertTrue("date > '/etc/workbench/sample.ready'" in script)
        env.toHost(addon.marker).apply { parentFile.mkdirs(); writeText("ready") }
        assertTrue(setup.done)
        assertFalse("echo sample-addon" in LinuxSetup(env, Shell { error("not invoked") }).script())
    }

    @Test fun setupUpgradeRetainsCustomizedAndDisabledConnectors() {
        val disabled = McpServerSpec("filesystem", "mcp-server-filesystem /workspace", enabled = false)
        val custom = McpServerSpec("sample-plugin", "https://local.example/mcp", enabled = false)
        val extra = McpServerSpec("my-tools", "my-mcp")
        val upgraded = LinuxSetup.mergeDefaultServers(listOf(disabled, custom, extra))
        assertFalse(upgraded.first().enabled)
        assertEquals("mcp-server-filesystem /workspace", upgraded.first().target)
        assertEquals(custom, upgraded[1])
        assertEquals(extra, upgraded[2])
        assertEquals(upgraded, LinuxSetup.mergeDefaultServers(upgraded))
        val customFilesystem = McpServerSpec("filesystem", "mcp-server-filesystem /workspace/my-project")
        assertEquals(customFilesystem, LinuxSetup.mergeDefaultServers(listOf(customFilesystem)).first())
        assertEquals(LinuxSetup.DEFAULT_SERVERS, LinuxSetup.mergeDefaultServers(emptyList()))
    }

    @Test fun reportsActualErrorAtEndOfLongInstallLog() = runBlocking {
        val env = Env(root)
        val log = env.toHost(LinuxSetup.LOG)
        val shell = Shell {
            log.parentFile.mkdirs()
            log.writeText("下载成功\n".repeat(40_000) + "\u001b[31mE: Java 软件包解压失败，空间不足\u001b[0m\n")
            CommandResult(100, "下载成功\n".repeat(6000), false, true)
        }
        val failure = runCatching { LinuxSetup(env, shell).run() }.exceptionOrNull() ?: fail("expected failure")
        assertTrue("Java 软件包解压失败，空间不足" in failure.message.orEmpty())
        assertFalse("\u001b" in failure.message.orEmpty())
        assertTrue(log.length() > 128 * 1024)
        val tail = SetupDiagnostics.tail(log, 1025)
        assertFalse('\ufffd' in tail)
        assertTrue(tail.endsWith("空间不足\n"))
    }

    @Test fun failureBeforeLoggingDoesNotReportPreviousAttempt() = runBlocking {
        val env = Env(root)
        env.toHost(LinuxSetup.LOG).apply { parentFile.mkdirs(); writeText("旧记录：完成\n") }
        val shell = Shell { CommandResult(75, "环境初始化正在运行，请等待当前安装完成。\n", false, false) }
        val failure = runCatching { LinuxSetup(env, shell).run() }.exceptionOrNull() ?: fail("expected failure")
        assertTrue("正在运行" in failure.message.orEmpty())
        assertFalse("旧记录" in failure.message.orEmpty())
    }

    @Test fun reportStartsWithActualFailedStageAndReasonBeforeStackTrace() = runBlocking {
        val env = Env(root)
        val stage = "修复上次中断的软件包依赖"
        val log = "== $stage ==\nE: Unmet dependencies: node-es-abstract\n" +
            "安装失败：$stage（退出码 1）\n日志：${LinuxSetup.LOG}\n"
        val shell = Shell {
            env.toHost(LinuxSetup.LOG).apply { parentFile.mkdirs(); writeText(log) }
            // Fail immediately, before the 500 ms progress watcher can see the new stage.
            CommandResult(1, log, false, false)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val p = LinuxProvisioner(env, shell, scope, File(root, "diagnostics"))
        try {
            p.start()
            withTimeout(5000) { while (p.running) delay(10) }
            val report = p.failureReport()
            assertTrue("阶段：$stage" in report)
            assertEquals(stage, p.detail)
            assertEquals("E: Unmet dependencies: node-es-abstract", p.error)
            val reason = report.indexOf("失败原因：")
            val error = report.indexOf("E: Unmet dependencies")
            val localLog = report.indexOf("本地保留日志")
            val stack = report.indexOf("技术调用栈")
            assertTrue(reason >= 0 && reason < error && error < localLog && localLog < stack)
            assertFalse("\tat " in report.substring(0, stack))
            assertTrue("LinuxSetup.run" in report.substring(stack))
            assertEquals(report, File(root, "diagnostics/failure.txt").readText())
        } finally { scope.cancel() }
    }

    @Test fun currentLogWithSameTimestampStillReportsThisAttemptsError() = runBlocking {
        val env = Env(root)
        val log = env.toHost(LinuxSetup.LOG).apply { parentFile.mkdirs(); writeText("previous attempt\n") }
        val originalTime = log.lastModified()
        val shell = Shell {
            log.writeText("== 配置 Java 21 ==\nE: actual Java configuration failure\n")
            assertTrue(log.setLastModified(originalTime))
            CommandResult(1, "truncated command output", false, true)
        }
        val failure = runCatching { LinuxSetup(env, shell).run() }.exceptionOrNull() ?: fail("expected failure")
        assertTrue("actual Java configuration failure" in failure.message.orEmpty())
        assertEquals("配置 Java 21", (failure as LinuxSetupFailure).stage)
        assertFalse("previous attempt" in failure.message.orEmpty())
    }

    @Test fun progressShowsActualStageAndKeepsRetryActiveUntilCompletion() = runBlocking {
        val env = Env(root)
        val finish = CompletableDeferred<Unit>()
        val stage = "修复上次中断的软件包依赖"
        val shell = Shell {
            env.toHost("/tmp/rw-setup-step").apply { parentFile.mkdirs(); writeText(stage) }
            finish.await()
            env.toHost(LinuxSetup.MARKER).apply { parentFile.mkdirs(); writeText("complete") }
            CommandResult(0, "完成", false, false)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val p = LinuxProvisioner(env, shell, scope, File(root, "diagnostics"))
        try {
            p.start()
            withTimeout(5000) { while (p.detail != stage) delay(10) }
            assertTrue(p.running)
            assertFalse(p.ready)
            assertEquals(LinuxProvisioner.Step.PACKAGES, p.step)
            finish.complete(Unit)
            withTimeout(5000) { while (p.running) delay(10) }
            assertTrue(p.ready)
            assertEquals(null, p.error)
        } finally { scope.cancel() }
    }

    @Test fun previousSetupMarkerRequiresUpgradeAndSuccessRequiresNewMarker() = runBlocking {
        val env = Env(root)
        env.toHost("/etc/rustwright/setup-v2").apply { parentFile.mkdirs(); writeText("old") }
        env.toHost("/etc/rustwright/setup-alpine-v3").writeText("Codex 0.160.0")
        env.toHost("/etc/rustwright/setup-alpine-v4").writeText("previous helper experiment")
        env.toHost("/etc/rustwright/setup-alpine-v5").writeText("Codex 0.166.0 without host")
        val setup = LinuxSetup(env, Shell { CommandResult(0, "", false, false) })
        assertFalse(setup.done)
        assertTrue(runCatching { setup.run() }.isFailure)
        env.toHost(LinuxSetup.MARKER).writeText("new")
        assertTrue(setup.done)
        assertEquals("初始化完成", setup.run())
    }

    @Test fun installerVersionAndCompletionMarkerMatchUpgradeGate() {
        assertTrue("CODEX_VERSION=${LinuxSetup.CODEX_VERSION}\n" in LinuxSetup.SCRIPT)
        assertTrue("MARKER=${LinuxSetup.MARKER}\n" in LinuxSetup.SCRIPT)
        assertFalse("@@" in LinuxSetup.SCRIPT)
    }

    @Test fun downloadFailureSurvivesWithoutRootfsAndDoesNotStartPackages() = runBlocking {
        val env = object : Env(root) {
            override val installed = false
            override fun install(log: (String) -> Unit) {
                log("读取 mirror/SHA256SUMS")
                log("从 mirror 安装失败：连接超时")
                error("所有系统下载源连接失败")
            }
        }
        val busy = CopyOnWriteArrayList<Boolean>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val diagnostics = File(root, "app-diagnostics")
        val p = LinuxProvisioner(env, Shell { fail("must not install packages") }, scope, diagnostics, busy::add)
        try {
            p.start()
            withTimeout(5000) { while (p.running) delay(10) }
            assertTrue("所有系统下载源连接失败" in p.error.orEmpty())
            assertTrue("从 mirror 安装失败：连接超时" in p.failureReport())
            assertEquals(p.failureReport(), File(diagnostics, "failure.txt").readText())
            assertEquals(listOf(true, false), busy.toList())
        } finally { scope.cancel() }
    }

    @Test fun concurrentStartsRunOnceAndCancellationReleasesBackgroundWork() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val busy = CopyOnWriteArrayList<Boolean>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val shell = Shell {
            calls.incrementAndGet()
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                released.complete(Unit)
                withContext(NonCancellable) { cleanup.await() }
            }
        }
        val p = LinuxProvisioner(Env(root), shell, scope, File(root, "diagnostics"), busy::add)
        try {
            (1..20).map { launch(Dispatchers.Default) { p.start() } }.joinAll()
            withTimeout(5000) { entered.await() }
            assertEquals(1, calls.get())
            scope.cancel()
            withTimeout(5000) { released.await() }
            assertTrue(p.running, "cleanup is still releasing the terminal and background task")
            cleanup.complete(Unit)
            withTimeout(5000) { while (p.running) delay(10) }
            assertEquals(listOf(true, false), busy.toList())
            assertEquals(null, p.error)
        } finally { cleanup.complete(Unit); scope.cancel() }
    }

    private open class Env(private val root: File) : LinuxEnvironment {
        override val installed = true
        override fun install(log: (String) -> Unit): Unit = error("unexpected install")
        override fun start(command: String, workDir: String, env: Map<String, String>, mergeStderr: Boolean): Process = error("unexpected start")
        override fun toHost(guestPath: String) = File(root, guestPath.removePrefix("/"))
        override fun toGuest(host: File) = "/" + host.relativeTo(root).path
    }

    private class Shell(private val result: suspend () -> CommandResult) : AgentShell {
        override val ready = true
        override fun note(text: String) {}
        override suspend fun run(command: String, title: String, workDir: String, timeoutMillis: Long, quiet: Boolean, outputLimit: Int) = result()
    }
}
