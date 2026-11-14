package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.runtime.LifecycleDispatcher
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.core.shell.PtyCommand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 真 PTY / 本地 bash；不连接 Ubuntu 下载、模型或任何远端服务。 */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class TerminalBackgroundTest {
    @Test
    fun coldAgentTerminalConsumesMoreThanPtyBufferInBackgroundAndHandsBackToUi() = runBlocking {
        val root = Files.createTempDirectory("terminal-background").toFile()
        val ui = ManualDispatcher()
        val background = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val dispatcher = LifecycleDispatcher(ui, background)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val env = object : LinuxEnvironment {
            override val installed = true
            override val managed = false
            override fun install(log: (String) -> Unit) = error("unused")
            override fun start(command: String, workDir: String, env: Map<String, String>, mergeStderr: Boolean): Process = error("unused")
            override fun shellCommand(workDir: String) = PtyCommand(
                "/bin/bash", listOf("bash", "--noprofile", "--norc"),
                mapOf("PATH" to "/usr/bin:/bin", "TERM" to "xterm-256color", "PS1" to ""), root.path,
            )
            override fun toHost(guestPath: String) = File(guestPath)
            override fun toGuest(host: File) = host.absolutePath
        }
        var redraws = 0
        val sessions = TerminalSessions(env, scope, dispatcher, ui) { redraws++ }
        val shell = TerminalAgentShell(env, sessions, dispatcher, File(root, "stage").path)
        try {
            dispatcher.pause()
            val result = withTimeout(15_000) {
                shell.run("head -c 262144 /dev/zero | tr '\\0' x; printf '\\nbackground-finished\\n'", title = "background", workDir = root.path, timeoutMillis = 10_000, outputLimit = 300_000)
            }
            assertEquals(0, result.exitCode)
            assertFalse(result.timedOut)
            assertFalse(result.truncated)
            assertTrue(result.output.endsWith("background-finished\n"))
            assertTrue(result.output.length > 64 * 1024)
            assertEquals(0, redraws) // 后台只更新模拟器，Arc UI 回调保留至恢复。
            val session = withContext(dispatcher) {
                sessions.entries.single().session.also { it.pump() }
            }
            dispatcher.resume()
            ui.drain()
            assertEquals(1, redraws)
            assertTrue("background-finished" in session.emulator.screen.transcriptText)
            session.resize(90, 25)
            assertEquals(90, session.emulator.mColumns)

            dispatcher.pause()
            val next = withTimeout(5000) { shell.run("echo second", workDir = root.path, timeoutMillis = 3000) }
            assertEquals(0, next.exitCode)
            assertEquals("second\n", next.output)
            withContext(dispatcher) { assertEquals(1, sessions.entries.size) }
        } finally {
            dispatcher.pause()
            withContext(dispatcher) { sessions.shutdown() }
            scope.cancel()
            dispatcher.resume()
            ui.drain()
            background.close()
            root.deleteRecursively()
        }
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { synchronized(tasks) { tasks.addLast(block) } }
        fun drain() {
            while (true) {
                val next = synchronized(tasks) { tasks.pollFirst() } ?: return
                next.run()
            }
        }
    }
}
