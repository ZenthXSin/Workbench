package io.github.zenthxsin.rustwright.core.runtime.archive

import java.io.File
import java.io.InputStream

object TarArchive {
    /** 最小 tar 解包：普通文件、目录、软链接、硬链接（复制）、GNU 长文件名、pax path。 */
    fun extract(input: InputStream, dest: File,
        symlink: (String, String) -> Unit = { link, path -> java.nio.file.Files.createSymbolicLink(File(path).toPath(), java.nio.file.Paths.get(link)); Unit },
        chmod: (String, Int) -> Unit = { _, _ -> },
    ): Int {
        val root = dest.canonicalFile.toPath()
        fun safe(name: String): File {
            require(!name.startsWith('/') && !name.contains('\\') && !name.contains(':') && name.split('/').none { it == ".." }) { "tar 路径越界" }
            val file = File(dest, name).canonicalFile
            require(file.toPath().startsWith(root)) { "tar 链接越界" }
            return file
        }
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var count = 0
        var entries = 0
        var expanded = 0L
        val hardLinks = mutableListOf<Pair<File, File>>()
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it.toInt() == 0 }) break
            fun str(off: Int, len: Int): String {
                var end = off
                while (end < off + len && header[end].toInt() != 0) end++
                return String(header, off, end - off, Charsets.UTF_8)
            }
            fun oct(off: Int, len: Int) = str(off, len).trim().ifEmpty { "0" }.toLong(8)
            val checksum = header.indices.sumOf { if (it in 148..155) 32 else header[it].toInt() and 255 }
            require(oct(148, 8) == checksum.toLong()) { "tar header checksum mismatch" }
            val size = oct(124, 12)
            require(++entries <= 200_000 && size in 0..(512L * 1024 * 1024)) { "tar 条目超限" }
            expanded += size
            require(expanded <= 4L * 1024 * 1024 * 1024) { "tar 解压体积超限" }
            val type = header[156].toInt().toChar()
            val prefix = if (str(257, 6).startsWith("ustar")) str(345, 155) else ""
            var name = longName ?: (if (prefix.isNotEmpty()) "$prefix/" else "") + str(0, 100)
            val link = longLink ?: str(157, 100)
            longName = null; longLink = null
            val data = if (type == 'L' || type == 'K' || type == 'x' || type == 'g') readBytes(input, size) else null
            when (type) {
                'L' -> { longName = String(data!!, Charsets.UTF_8).trimEnd('\u0000'); continue }
                'K' -> { longLink = String(data!!, Charsets.UTF_8).trimEnd('\u0000'); continue }
                'x' -> {
                    String(data!!, Charsets.UTF_8).lineSequence().forEach { rec ->
                        val kv = rec.substringAfter(' ', "")
                        if (kv.startsWith("path=")) longName = kv.removePrefix("path=")
                        if (kv.startsWith("linkpath=")) longLink = kv.removePrefix("linkpath=")
                    }
                    continue
                }
                'g' -> continue
            }
            name = name.removePrefix("./")
            if (name.isEmpty()) { skip(input, size); continue }
            val target = safe(name)
            val mode = oct(100, 8).toInt() and 0x1ff
            when (type) {
                '5' -> target.mkdirs()
                '2' -> {
                    target.parentFile?.mkdirs()
                    val linked = (if (link.startsWith('/')) File(dest, link.trimStart('/')) else File(target.parentFile, link)).canonicalFile
                    require(linked.toPath().startsWith(root) && linked != target) { "tar 符号链接越界" }
                    require(!target.exists() && !java.nio.file.Files.isSymbolicLink(target.toPath())) { "tar 重复链接" }
                    symlink(target.parentFile!!.toPath().relativize(linked.toPath()).toString(), target.path)
                }
                '1' -> hardLinks += target to safe(link.removePrefix("./"))
                '0', '\u0000', '7' -> {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> copyN(input, out, size) }
                    skip(input, (512 - size % 512) % 512)
                    chmod(target.path, mode)
                    count++
                    continue
                }
            }
            if (type != '1' && type != '2') runCatching { chmod(target.path, mode) }
            skip(input, (size + 511) / 512 * 512)
            count++
        }
        hardLinks.forEach { (to, from) ->
            require(to.canonicalFile.toPath().startsWith(root) && from.canonicalFile.toPath().startsWith(root) && from.isFile) { "tar 硬链接越界" }
            require(!java.nio.file.Files.isSymbolicLink(to.toPath())) { "tar 硬链接目标无效" }
            expanded += from.length()
            require(expanded <= 4L * 1024 * 1024 * 1024) { "tar 解压体积超限" }
            to.parentFile?.mkdirs(); from.copyTo(to, overwrite = true); to.setExecutable(from.canExecute())
        }
        return count
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) { check(off == 0) { "tar 意外结束" }; return false }
            off += n
        }
        return true
    }

    private fun readBytes(input: InputStream, size: Long): ByteArray {
        require(size in 0..(1024L * 1024)) { "tar 元数据超限" }
        val out = ByteArray(size.toInt())
        check(readFully(input, out)) { "tar 意外结束" }
        skip(input, (512 - size % 512) % 512)
        return out
    }

    private fun copyN(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) error("tar 意外结束")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) { check(input.read() >= 0) { "tar 意外结束" }; left-- } else left -= s
        }
    }
}
