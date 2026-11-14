package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import io.github.zenthxsin.rustwright.core.shell.AgentShell
import io.github.zenthxsin.rustwright.core.shell.CommandResult
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 在终端页的「助手」会话里执行命令：脚本、说明写到 /tmp/rw，往 PTY 里敲 `rw_run <id>`，
 * 会话里的 shell 函数负责显示说明、执行、把输出 tee 到日志、写退出码文件；这边轮询退出码文件。
 * 会话关闭了回显，用户看到的是「› 说明」和命令输出，而不是内部的 rw_run 调用。
 * 一次只跑一条（[lock]）；超时或协程取消时向会话发 Ctrl+C。
 */
class TerminalAgentShell(
    private val env: LinuxEnvironment,
    private val sessions: TerminalSessions,
    private val state: CoroutineDispatcher = ArcDispatcher,
    /** 命令脚本和结果的暂存目录；测试用隔离目录运行本地 PTY。 */
    private val stage: String = STAGE,
) : AgentShell {

    private val lock = Mutex()
    private val ids = AtomicLong(System.currentTimeMillis() % 100_000 * 1000)
    private val dir: File get() = env.toHost(stage)
    @Volatile private var initialized: TerminalSessions.Entry? = null

    override val ready: Boolean get() = env.installed && env.shellCommand() != null

    override suspend fun run(command: String, title: String, workDir: String, timeoutMillis: Long, quiet: Boolean, outputLimit: Int): CommandResult = lock.withLock {
        check(env.installed) { "内置 Linux 尚未安装，请先在「终端」页安装" }
        val entry = ensureSession()
        val id = ids.incrementAndGet().toString()
        val stage = dir.apply { mkdirs() }
        File(stage, "$id.sh").writeText("cd ${quote(workDir)} || exit 1\n$command\n")
        File(stage, "$id.title").writeText(title.lineSequence().first().take(200) + if (title.contains('\n')) " …" else "")
        val exit = File(stage, "$id.exit")
        val log = File(stage, "$id.log")
        try {
            entry.session.write("rw_run $id ${if (quiet) 1 else 0} ${outputLimit.coerceIn(1, 64 * 1024 * 1024) + 1}\n")
            val code = withTimeoutOrNull(timeoutMillis) { awaitFile(exit, entry) }
            if (code == null) {
                entry.session.writeCodePoint('c'.code, ctrl = true)
                withTimeoutOrNull(3_000) { awaitFile(exit, entry) }
            }
            readResult(log, code, outputLimit)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { entry.session.writeCodePoint('c'.code, ctrl = true) }
            throw e
        } finally {
            listOf("sh", "title", "exit", "log").forEach { File(stage, "$id.$it").delete() }
        }
    }

    override fun note(text: String) {
        val entry = initialized?.takeIf { it.session.running } ?: return
        val id = "n" + ids.incrementAndGet()
        File(dir.apply { mkdirs() }, "$id.note").writeText(text.replace('\n', ' ').take(300))
        entry.session.write("rw_note $id\n")
    }

    private fun readResult(log: File, code: Int?, limit: Int): CommandResult {
        val maximum = limit.coerceIn(1, 64 * 1024 * 1024)
        val bytes = if (log.isFile) log.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() < maximum) {
                val n = input.read(buffer, 0, minOf(buffer.size, maximum - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } else ByteArray(0)
        val truncated = log.length() > maximum
        val text = bytes.decodeToString()
        return CommandResult(code, text, timedOut = code == null, truncated = truncated)
    }

    private suspend fun awaitFile(file: File, entry: TerminalSessions.Entry): Int {
        var wait = 20L
        while (true) {
            if (file.isFile) file.readText().trim().toIntOrNull()?.let { return it }
            check(entry.session.running) { "${AppBrand.identity.assistant}的终端会话已结束" }
            delay(wait)
            wait = (wait * 2).coerceAtMost(200)
        }
    }

    /** 确保助手会话在跑并已加载 rw_run 等函数。 */
    private suspend fun ensureSession(): TerminalSessions.Entry {
        initialized?.takeIf { it.session.running }?.let { return it }
        val entry = withContext(state) { sessions.agent() } ?: error("此平台不支持终端会话")
        val files = dir.apply { mkdirs() }
        val ready = File(files, "ready").apply { delete() }
        File(files, "agentrc").writeText(RC.replace(STAGE, quote(stage)))
        entry.session.write("source ${quote("$stage/agentrc")}\n")
        // proot 冷启动在慢设备上可能要几十秒
        withTimeoutOrNull(90_000) {
            while (!ready.exists()) {
                check(entry.session.running) { "${AppBrand.identity.assistant}的终端会话启动失败" }
                delay(50)
            }
        } ?: error("${AppBrand.identity.assistant}的终端会话没有响应")
        initialized = entry
        return entry
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        const val STAGE = "/tmp/rw"

        private val RC = """
            stty -echo 2>/dev/null
            PS1='\[\e[38;5;208m\]${AppBrand.identity.assistant}\[\e[0m\] \w \$ '
            rw_run() {
              local id=${'$'}1 q=${'$'}2 cap=${'$'}3 d=$STAGE c=0
              trap "echo 130 > ${'$'}d/${'$'}id.exit" INT
              printf '\e[38;5;208m>\e[0m \e[1m%s\e[0m\n' "${'$'}(cat ${'$'}d/${'$'}id.title)"
              if [ "${'$'}q" = 1 ]; then
                bash ${'$'}d/${'$'}id.sh 2>&1 | { head -c "${'$'}cap"; cat >/dev/null; } > ${'$'}d/${'$'}id.log; c=${'$'}{PIPESTATUS[0]}
                printf '\e[2m  %s 行\e[0m\n' "${'$'}(wc -l < ${'$'}d/${'$'}id.log)"
              else
                bash ${'$'}d/${'$'}id.sh 2>&1 | { head -c "${'$'}cap"; cat >/dev/null; } | tee ${'$'}d/${'$'}id.log; c=${'$'}{PIPESTATUS[0]}
              fi
              [ "${'$'}c" -ne 0 ] && printf '\e[31m  退出码 %s\e[0m\n' "${'$'}c"
              trap - INT
              echo ${'$'}c > ${'$'}d/${'$'}id.exit.tmp && mv ${'$'}d/${'$'}id.exit.tmp ${'$'}d/${'$'}id.exit
            }
            rw_note() { printf '\e[2m# %s\e[0m\n' "${'$'}(cat $STAGE/${'$'}1.note)"; rm -f $STAGE/${'$'}1.note; }
            clear
            printf '\e[2m# ${AppBrand.identity.assistant}的命令和文件操作会显示在这里，Ctrl+C 可以打断\e[0m\n'
            touch $STAGE/ready
        """.trimIndent() + "\n"
    }
}
