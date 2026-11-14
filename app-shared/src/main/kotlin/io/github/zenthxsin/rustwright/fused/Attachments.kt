package io.github.zenthxsin.rustwright.fused

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 多模态输入：用户发的图片、文件先复制进项目的 `tmp/`，消息里只带路径。
 * 发给助手的消息末尾每个附件一行 `[附件] tmp/xxx.png`（路径相对项目目录）；助手用 view_image 看图、直接读文件。
 * 对话气泡里再把这些行拆出来显示成附件卡片。
 */
object Attachments {
    const val TAG = "[附件] "
    const val DIR = "tmp"

    private val images = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    fun isImage(path: String) = path.substringAfterLast('.', "").lowercase() in images

    /** 正文 + 附件行。 */
    fun compose(text: String, paths: List<String>): String =
        if (paths.isEmpty()) text else (text.trim() + "\n\n" + paths.joinToString("\n") { TAG + it }).trim()

    /** 拆回正文和附件路径（只认末尾连续的附件行）。 */
    fun split(message: String): Pair<String, List<String>> {
        val lines = message.trimEnd().lines()
        var i = lines.size
        while (i > 0 && lines[i - 1].startsWith(TAG)) i--
        if (i == lines.size) return message to emptyList()
        return lines.subList(0, i).joinToString("\n").trimEnd() to lines.subList(i, lines.size).map { it.removePrefix(TAG).trim() }
    }

    /** 复制进 `<项目>/tmp/`，返回相对项目目录的路径。文件名前加时间，避免同名覆盖。 */
    fun copyIn(projectDir: File, src: File): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val safe = src.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").takeLast(80).ifBlank { "file" }
        val dir = File(projectDir, DIR).apply { mkdirs() }
        var dest = File(dir, "$stamp-$safe")
        var n = 2
        while (dest.exists()) dest = File(dir, "$stamp-${n++}-$safe")
        src.copyTo(dest)
        return "$DIR/${dest.name}"
    }
}
