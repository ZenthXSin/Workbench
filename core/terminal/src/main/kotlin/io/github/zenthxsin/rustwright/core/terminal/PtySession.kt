package io.github.zenthxsin.rustwright.core.terminal

import com.termux.terminal.ByteQueue
import com.termux.terminal.JNI
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import io.github.zenthxsin.rustwright.core.shell.PtyCommand
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * 一个伪终端会话（替代 Termux 的 TerminalSession，不依赖 Android Handler）：
 * 读线程把输出放进队列，[pump] 在与 UI 串行的状态线程上喂给 [emulator]。
 * Android 暂停期间可由生命周期协调的后台线程接管；不能与 UI 并行访问模拟器。
 * 需要 `libtermux.so`（platform/android 编译），只能在 Android 上运行。
 */
class PtySession(
    private val command: PtyCommand,
    columns: Int,
    rows: Int,
    private val cellWidth: Int = 1,
    private val cellHeight: Int = 1,
    transcriptRows: Int = 2000,
) : TerminalOutput() {

    private val client = object : TerminalSessionClient {
        override fun getTerminalCursorStyle(): Int? = null
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun logError(tag: String, message: String) = System.err.println("$tag: $message")
        override fun logWarn(tag: String, message: String) = System.err.println("$tag: $message")
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = System.err.println("$tag: $message $e")
        override fun logStackTrace(tag: String, e: Exception) = System.err.println("$tag: $e")
    }

    // 回调必须在 emulator 之前初始化：TerminalEmulator 构造时就会回调 onColorsChanged 等
    /** 有新输出或状态变化时回调，可能在读线程上；只发送状态泵信号，不直接访问 UI。 */
    var onChanged: () -> Unit = {}
    var onClipboard: (String) -> Unit = {}
    var onPaste: () -> Unit = {}

    val emulator = TerminalEmulator(this, columns, rows, cellWidth, cellHeight, transcriptRows, client)
    private val incoming = ByteQueue(64 * 1024)
    private val outgoing = LinkedBlockingQueue<ByteArray>()
    private val buffer = ByteArray(64 * 1024)
    private var fd = -1

    @Volatile var pid = 0; private set
    @Volatile var exitCode: Int? = null; private set
    @Volatile var title: String? = null; private set

    val running: Boolean get() = pid > 0 && exitCode == null

    fun start() {
        check(fd < 0) { "already started" }
        val pidOut = IntArray(1)
        fd = JNI.createSubprocess(
            command.executable, command.cwd, command.args.toTypedArray(),
            command.env.map { (k, v) -> "$k=$v" }.toTypedArray(), pidOut,
            emulator.mRows, emulator.mColumns, cellWidth, cellHeight,
        )
        pid = pidOut[0]
        // 桌面 JDK 需要 --add-opens java.base/java.io=ALL-UNNAMED 才能反射 FileDescriptor（桌面启动器已加）
        val descriptor = wrap(fd)
        fun openIn() = FileInputStream(descriptor)
        fun openOut() = FileOutputStream(descriptor)
        thread(name = "pty-read-$pid", isDaemon = true) {
            runCatching {
                openIn().use { input ->
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0 || !incoming.write(buf, 0, n)) break
                        onChanged()
                    }
                }
            }
        }
        thread(name = "pty-write-$pid", isDaemon = true) {
            runCatching {
                openOut().use { out ->
                    while (true) {
                        val bytes = outgoing.take()
                        if (bytes.isEmpty()) break
                        out.write(bytes)
                    }
                }
            }
        }
        thread(name = "pty-wait-$pid", isDaemon = true) {
            val code = JNI.waitFor(pid)
            exitCode = code
            onChanged()
        }
    }

    /** 状态线程调用：每批最多处理 64 KiB，持续输出时也能让出线程进行生命周期交接。 */
    fun pump(): Boolean {
        val n = incoming.read(buffer, false)
        if (n <= 0) return false
        emulator.append(buffer, n)
        return true
    }

    fun resize(columns: Int, rows: Int) {
        if (columns == emulator.mColumns && rows == emulator.mRows) return
        emulator.resize(columns, rows, cellWidth, cellHeight)
        if (fd >= 0) JNI.setPtyWindowSize(fd, rows, columns, cellWidth, cellHeight)
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (count > 0 && running) outgoing.put(data.copyOfRange(offset, offset + count))
    }

    /** 输入一个 Unicode 码点；[ctrl] 时转成控制字符，[alt] 时前缀 ESC。 */
    fun writeCodePoint(codePoint: Int, ctrl: Boolean = false, alt: Boolean = false) {
        var cp = codePoint
        if (ctrl) cp = when (cp) {
            in 'a'.code..'z'.code -> cp - 'a'.code + 1
            in 'A'.code..'Z'.code -> cp - 'A'.code + 1
            ' '.code, '2'.code -> 0
            '['.code, '3'.code -> 27
            '\\'.code, '4'.code -> 28
            ']'.code, '5'.code -> 29
            '^'.code, '6'.code -> 30
            '_'.code, '7'.code, '/'.code -> 31
            '8'.code -> 127
            else -> cp
        }
        val text = (if (alt) "\u001b" else "") + String(Character.toChars(cp))
        write(text)
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) { title = newTitle; onChanged() }
    override fun onCopyTextToClipboard(text: String) = onClipboard(text)
    override fun onPasteTextFromClipboard() = onPaste()
    override fun onBell() {}
    override fun onColorsChanged() = onChanged()

    /** 结束会话：杀进程（SIGKILL），关闭 fd。 */
    fun finish() {
        if (pid > 0 && exitCode == null) kill(pid)
        outgoing.put(ByteArray(0))
        incoming.close()
        if (fd >= 0) { JNI.close(fd); fd = -1 }
    }

    private fun kill(pid: Int) {
        runCatching {
            Class.forName("android.system.Os").getMethod("kill", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(null, pid, 9)
        }.onFailure { runCatching { ProcessHandle.of(pid.toLong()).ifPresent { it.destroyForcibly() } } }
    }

    private fun wrap(fd: Int): FileDescriptor {
        val result = FileDescriptor()
        val field = runCatching { FileDescriptor::class.java.getDeclaredField("descriptor") }
            .getOrElse { FileDescriptor::class.java.getDeclaredField("fd") }
        field.isAccessible = true
        field.setInt(result, fd)
        return result
    }
}
