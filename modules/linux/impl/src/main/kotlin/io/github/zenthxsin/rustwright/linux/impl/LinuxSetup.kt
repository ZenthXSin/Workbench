package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import io.github.zenthxsin.rustwright.core.agent.McpServerSpec
import io.github.zenthxsin.rustwright.core.shell.AgentShell
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment

/** Prepare the Alpine environment in the visible agent terminal. The same script is exercised in an isolated chroot. */
class LinuxSetup(private val env: LinuxEnvironment, private val shell: AgentShell, private val addons: () -> List<io.github.zenthxsin.rustwright.core.shell.EnvironmentAddon> = { emptyList() }) {
    val done: Boolean get() = !env.managed || env.toHost(MARKER).isFile && addons().all { env.toHost(it.marker).isFile }

    suspend fun run(): String {
        check(env.managed) { "${env.distro} 不需要初始化" }
        val logFile = env.toHost(LOG)
        val previousLogTime = logFile.lastModified()
        val previousLogLength = logFile.length()
        val result = shell.run(
            script(), title = "初始化虚拟环境：git、Node 24、Python、${AppBrand.identity.assistant}工具",
            timeoutMillis = 4 * 60 * 60_000L,
        )
        if (result.exitCode != 0 || result.timedOut) {
            // The script can fail before opening its log (lock, permissions, disk full).
            // Never substitute a stale log from another attempt for this invocation's error.
            val hasCurrentLog = (logFile.lastModified() != previousLogTime || logFile.length() != previousLogLength) && result.exitCode != 75
            val log = (if (hasCurrentLog) SetupDiagnostics.tail(logFile) else "").ifBlank { result.output }
            val tail = log.lineSequence().filter { it.isNotBlank() }.toList().takeLast(8).joinToString("\n")
            throw LinuxSetupFailure(
                (if (result.timedOut) "初始化超时" else "初始化失败（退出码 ${result.exitCode}）") + "\n$tail\n完整日志：$LOG",
                SetupDiagnostics.stage(log),
            )
        }
        check(env.toHost(MARKER).isFile) { "初始化未完成，请查看 $LOG" }
        return "初始化完成"
    }

    internal fun script(): String {
        val fragments = addons().joinToString("\n") { addon ->
            "if [ ! -f '${addon.marker}' ]; then\n" + addon.script() + "\nmkdir -p '${java.io.File(addon.marker).parent}'\ndate > '${addon.marker}'\nfi\n"
        }
        return SCRIPT.replace("step \"清理缓存\"", fragments + "\nstep \"清理缓存\"")
    }

    companion object {
        /** Alpine 版初始化完成的标记（Ubuntu 版是 setup-v3）。 */
        const val MARKER = "/etc/rustwright/setup-alpine-v7"
        const val LOG = "/var/log/rustwright/setup.log"
        const val CODEX_VERSION = "0.166.0"

        val DEFAULT_SERVERS = listOf(
            McpServerSpec("filesystem", "mcp-server-filesystem ${LinuxEnvironment.WORKSPACE}"),
        )

        internal fun mergeDefaultServers(existing: List<McpServerSpec>): List<McpServerSpec> {
            val upgraded = existing.map { server ->
                if (server.name == "filesystem" && server.target.trim() == "mcp-server-filesystem ${LinuxEnvironment.WORKSPACE}") {
                    server.copy(target = DEFAULT_SERVERS.first().target)
                } else server
            }
            // Upgrade the original filesystem command without replacing custom targets or re-enabling disabled connectors.
            return upgraded + DEFAULT_SERVERS.filter { candidate -> upgraded.none { it.name == candidate.name } }
        }

        internal val SCRIPT: String by lazy {
            checkNotNull(io.github.zenthxsin.rustwright.core.runtime.Res.open("rustwright/setup.sh")) { "缺少虚拟环境安装脚本" }
                // Resources can also come from source archives with Windows line endings.
                .bufferedReader().use { it.readText().replace("\r\n", "\n") }
                // 镜像地址不写死在脚本里（见 Endpoints），运行前填进去
                .replace("@@MIRROR@@", io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.mirror())
                .replace("@@LEGACY_MIRROR@@", io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.legacyMirror())
                .replace("@@FILESYSTEM_PACKAGE@@", resource("filesystem-package.json"))
                .replace("@@FILESYSTEM_LOCK@@", resource("filesystem-package-lock.json"))
                .replace("@@ADDONS@@", "")
        }
        private fun resource(name: String): String = checkNotNull(io.github.zenthxsin.rustwright.core.runtime.Res.open("rustwright/$name"))
            .bufferedReader().use { it.readText().replace("\r\n", "\n") }
    }
}

/** Preserve the script's exact failing stage even when failure precedes the UI poll. */
internal class LinuxSetupFailure(message: String, val stage: String?) : IllegalStateException(message)
