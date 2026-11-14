package io.github.zenthxsin.rustwright.core.runtime.archive

import io.github.zenthxsin.rustwright.core.runtime.ImportedFiles
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** 在独立临时目录中完整校验后发布，不覆盖目录内已有文件。 */
object ZipArchive {
    fun extract(archive: File, project: File, checkCancelled: () -> Unit = {}): File {
        val root = project.canonicalFile.toPath()
        val source = archive.canonicalFile
        require(source.isFile && source.toPath().startsWith(root)) { "压缩包不在项目内" }
        val parent = source.parentFile
        val temp = Files.createTempDirectory(parent.toPath(), ".extract-").toFile()
        try {
            var expanded = 0L
            var count = 0
            ZipFile(source).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    checkCancelled()
                    val entry = entries.nextElement()
                    require(++count <= 20000) { "压缩包文件数量过多" }
                    val name = entry.name
                    require(name.isNotBlank() && !name.startsWith('/') && !name.contains('\\') && !name.contains(':') && name.split('/').none { it == ".." }) { "压缩包包含越界路径" }
                    val output = File(temp, name).canonicalFile
                    // 有些打包工具会带一个 "./" 或 "." 目录条目，指的就是根目录本身：跳过即可，不能整包拒绝
                    if (entry.isDirectory && output == temp.canonicalFile) continue
                    require(output.toPath().startsWith(temp.toPath()) && output != temp) { "压缩包路径无效" }
                    if (entry.isDirectory) { require(output.isDirectory || output.mkdirs()); continue }
                    require(!output.exists()) { "压缩包包含重复路径：$name" }
                    require(entry.size in 0..(512L * 1024 * 1024)) { "解压文件过大" }
                    require(output.parentFile.isDirectory || output.parentFile.mkdirs())
                    val crc = CRC32()
                    var bytes = 0L
                    zip.getInputStream(entry).use { input -> output.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancelled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            bytes += n; expanded += n
                            require(bytes <= 512L * 1024 * 1024 && expanded <= 2L * 1024 * 1024 * 1024) { "解压体积超过限制" }
                            crc.update(buffer, 0, n); out.write(buffer, 0, n)
                        }
                    } }
                    require(bytes == entry.size && crc.value == entry.crc) { "文件损坏：$name" }
                }
            }
            checkCancelled()
            val target = ImportedFiles.available(parent, source.nameWithoutExtension.ifBlank { "解压文件" })
            require(temp.renameTo(target)) { "无法保存解压结果" }
            return target
        } finally { temp.deleteRecursively() }
    }
}
