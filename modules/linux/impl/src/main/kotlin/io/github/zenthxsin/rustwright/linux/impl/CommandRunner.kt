package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.shell.AgentShell
import io.github.zenthxsin.rustwright.core.shell.CommandResult
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** 在内置 Linux 中后台执行命令（不经过终端）：限时、限输出，协程取消即杀掉进程树。 */
class CommandRunner(private val env: LinuxEnvironment) {

    suspend fun run(command: String, workDir: String = LinuxEnvironment.WORKSPACE, timeoutMillis: Long = 120_000, onLine: (String) -> Unit = {}): CommandResult {
        check(env.installed) { "内置 Linux 尚未安装，请先在「终端」页安装" }
        val process = env.start(command, workDir)
        val out = StringBuilder()
        var truncated = false
        try {
            val finished = withTimeoutOrNull(timeoutMillis) {
                runInterruptible(Dispatchers.IO) {
                    process.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(8192)
                        val line = StringBuilder()
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            val keep = minOf(count, (AgentShell.MAX_OUTPUT - out.length).coerceAtLeast(0))
                            out.append(buffer, 0, keep)
                            if (keep < count) truncated = true
                            for (i in 0 until keep) {
                                val ch = buffer[i]
                                if (ch == '\n') { onLine(line.toString()); line.setLength(0) }
                                else { line.append(ch); if (line.length == 8192) { onLine(line.toString()); line.setLength(0) } }
                            }
                        }
                        if (line.isNotEmpty()) onLine(line.toString())
                    }
                    process.waitFor()
                }
            }
            return if (finished == null) {
                process.destroyForcibly()
                CommandResult(null, out.toString(), timedOut = true, truncated = truncated)
            } else {
                CommandResult(finished, out.toString(), timedOut = false, truncated = truncated)
            }
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
        }
    }
}
