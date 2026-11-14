package io.github.zenthxsin.rustwright.linux.impl

import java.io.File
import java.io.RandomAccessFile

/** Read the end of a persistent log without loading a potentially very large file into memory. */
internal object SetupDiagnostics {
    private val ansi = Regex("\u001b\\[[0-?]*[ -/]*[@-~]")
    private val failedStage = Regex("(?m)^安装失败：(.+?)（退出码 [^）]+）\\s*$")
    private val startedStage = Regex("(?m)^== (.+) ==\\s*$")

    fun stage(log: String): String? = failedStage.findAll(log).lastOrNull()?.groupValues?.get(1)
        ?: startedStage.findAll(log).lastOrNull()?.groupValues?.get(1)

    /** The panel needs a short cause; the copy action retains the full diagnostic. */
    fun summary(message: String): String {
        val lines = message.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val reason = lines.lastOrNull {
            it.startsWith("E:") || it.startsWith("dpkg: error", ignoreCase = true) ||
                it.startsWith("ERROR:", ignoreCase = true) || it.startsWith("curl:")
        } ?: lines.lastOrNull {
            !it.startsWith("==") && !it.startsWith("日志：") && !it.startsWith("完整日志：") &&
                !it.startsWith("安装失败：") && !it.startsWith("初始化失败")
        } ?: lines.firstOrNull().orEmpty()
        return if (reason.length > 140) reason.take(137) + "…" else reason
    }

    fun tail(file: File, limit: Int = 128 * 1024): String = runCatching {
        if (!file.isFile) return ""
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            val offset = (size - limit).coerceAtLeast(0)
            input.seek(offset)
            val bytes = ByteArray((size - offset).toInt())
            input.readFully(bytes)
            // A byte limit may cut through a UTF-8 character. Skip its continuation bytes.
            var start = 0
            if (offset > 0) while (start < bytes.size && bytes[start].toInt() and 0xc0 == 0x80) start++
            val text = ansi.replace(bytes.decodeToString(start, bytes.size), "").replace("\r\n", "\n")
            (if (offset > 0) "（日志较长，以下为末尾 ${limit / 1024} KiB；完整内容保存在日志文件中）\n" else "") + text
        }
    }.getOrElse { "无法读取日志：${it.message}" }
}
