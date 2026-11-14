package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.shell.AgentShell
import io.github.zenthxsin.rustwright.core.shell.CommandResult
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.linux.impl.LinuxProvisioner
import io.github.zenthxsin.rustwright.linux.impl.LinuxSetup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvironmentStatusTest {
    @TempDir lateinit var root: File

    @Test fun leftoverExecutableDoesNotMarkAnIncompleteEnvironmentReady() {
        val env = Env(root)
        env.toHost("/usr/local/bin/codex").apply { parentFile.mkdirs(); writeText("left by old installer") }
        env.toHost("/etc/rustwright/setup-v2").apply { parentFile.mkdirs(); writeText("old") }
        assertEquals(EnvStatus(false, null, "初始化未完成"), environmentStatus(env, null))
    }

    @Test fun completedGuestWithAbsoluteCodexSymlinkIsReady() {
        val env = Env(root)
        val guestTarget = "/opt/rustwright-test-codex/bin/codex"
        env.toHost(guestTarget).apply { parentFile.mkdirs(); writeText("guest executable") }
        val link = env.toHost("/usr/local/bin/codex").apply { parentFile.mkdirs() }
        Files.createSymbolicLink(link.toPath(), Paths.get(guestTarget))
        assertFalse(link.exists(), "Host filesystem cannot resolve the guest's absolute link")
        env.toHost(LinuxSetup.MARKER).apply { parentFile.mkdirs(); writeText("complete") }
        assertEquals(EnvStatus(true, null, "运行中"), environmentStatus(env, null))
        env.installed = false
        assertFalse(environmentStatus(env, null).ready)
    }

    @Test fun actualFailedAndRetriedProvisionerControlsHomeReadiness() = runBlocking {
        val env = Env(root)
        env.toHost("/usr/local/bin/codex").apply { parentFile.mkdirs(); writeText("old") }
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var attempts = 0
        val shell = object : AgentShell {
            override val ready = true
            override fun note(text: String) {}
            override suspend fun run(command: String, title: String, workDir: String, timeoutMillis: Long, quiet: Boolean, outputLimit: Int): CommandResult {
                if (++attempts == 1) return CommandResult(100, "E: broken dependencies", false, false)
                entered.complete(Unit)
                finish.await()
                env.toHost(LinuxSetup.MARKER).apply { parentFile.mkdirs(); writeText("complete") }
                return CommandResult(0, "完成", false, false)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val p = LinuxProvisioner(env, shell, scope, File(root, "diagnostics"))
        try {
            p.start()
            withTimeout(5000) { while (p.running) delay(10) }
            assertFalse(environmentStatus(env, p).ready)
            assertTrue("初始化失败" in environmentStatus(env, p).detail)
            p.start()
            withTimeout(5000) { entered.await() }
            assertFalse(environmentStatus(env, p).ready)
            assertTrue("安装中" in environmentStatus(env, p).detail)
            finish.complete(Unit)
            withTimeout(5000) { while (p.running) delay(10) }
            assertEquals(EnvStatus(true, null, "运行中"), environmentStatus(env, p))
            assertEquals(2, attempts)
        } finally { scope.cancel() }
    }

    private class Env(private val root: File) : LinuxEnvironment {
        override var installed = true
        override fun install(log: (String) -> Unit): Unit = error("not used")
        override fun start(command: String, workDir: String, env: Map<String, String>, mergeStderr: Boolean): Process = error("not used")
        override fun toHost(guestPath: String) = File(root, guestPath.removePrefix("/"))
        override fun toGuest(host: File) = "/" + host.relativeTo(root).path
    }
}
