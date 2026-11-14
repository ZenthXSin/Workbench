package io.github.zenthxsin.rustwright.ui.editor
import java.io.File
object TextFiles {
    private val binary = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ogg", "wav", "mp3", "mp4", "zip", "jar", "so", "apk", "pdf", "woff", "ttf", "gz", "7z")
    fun isText(file: File): Boolean {
        if (!file.isFile || file.length() > 2 * 1024 * 1024 || file.extension.lowercase() in binary) return false
        return runCatching { file.inputStream().use { input ->
            val bytes = ByteArray(4096); val n = input.read(bytes)
            (0 until maxOf(n, 0)).none { bytes[it] == 0.toByte() }
        } }.getOrDefault(false)
    }
}
