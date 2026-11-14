package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 用宿主机的 /bin/sh 冒充内置 Linux，验证超时、截断和退出码（真实时间，不用虚拟时钟）。 */
class CommandRunnerTest {
    private val hostShell = object : LinuxEnvironment {
        override val installed = true
        override fun install(log: (String) -> Unit) {}
        override fun start(command: String, workDir: String, env: Map<String, String>, mergeStderr: Boolean): Process =
            ProcessBuilder(if (System.getProperty("os.name").startsWith("Windows")) "C:/Program Files/Git/bin/bash.exe" else "/bin/sh", "-c", command).redirectErrorStream(true).start()
        override fun toHost(guestPath: String) = java.io.File(guestPath)
        override fun toGuest(host: java.io.File): String = host.path
    }

    @Test
    fun runsCollectsAndTimesOut() = kotlinx.coroutines.runBlocking {
        val runner = CommandRunner(hostShell)
        val ok = runner.run("echo 你好; echo err 1>&2; exit 3")
        assertEquals(3, ok.exitCode)
        assertTrue("你好" in ok.output && "err" in ok.output)

        val slow = runner.run("sleep 5", timeoutMillis = 300)
        assertTrue(slow.timedOut)

        val big = runner.run("yes x | head -c 200000")
        assertTrue(big.truncated && big.output.length <= io.github.zenthxsin.rustwright.core.shell.AgentShell.MAX_OUTPUT + 10)

        // 工具只依赖 AgentShell：这里用后台执行的 runner 充当
        val tool = RunCommandTool(object : io.github.zenthxsin.rustwright.core.shell.AgentShell {
            override val ready = true
            override suspend fun run(command: String, title: String, workDir: String, timeoutMillis: Long, quiet: Boolean, outputLimit: Int) =
                runner.run(command, "/tmp", timeoutMillis)
            override fun note(text: String) {}
        })
        val r = tool.execute("u", buildJsonObject { put("command", "exit 1") })
        assertEquals("command_failed", r.error?.type)
        assertEquals("Exit code 0.\nhi\n", tool.execute("u", buildJsonObject { put("command", "echo hi") }).toAgent)
    }
}
