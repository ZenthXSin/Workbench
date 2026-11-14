package io.github.zenthxsin.rustwright.core.runtime

import java.io.File

/** 保留文件名；同名导入自动编号，绝不覆盖已有文件。 */
object ImportedFiles {
    fun available(directory: File, name: String): File {
        require(name.isNotBlank() && name !in setOf(".", "..") && '/' !in name && '\\' !in name && '\u0000' !in name)
        val base = directory.canonicalFile
        var file = File(base, name)
        val suffix = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && !name.startsWith('.') }?.let { ".$it" }.orEmpty()
        val stem = name.removeSuffix(suffix)
        var index = 2
        while (file.exists()) file = File(base, "$stem (${index++})$suffix")
        require(file.canonicalFile.parentFile == base) { "导入路径越界" }
        return file
    }

    fun copy(source: File, directory: File, checkCancelled: () -> Unit = {}): File {
        require(directory.isDirectory || directory.mkdirs()) { "无法创建导入目录" }
        var target: File
        do { target = available(directory, source.name) } while (!target.createNewFile())
        try {
            source.inputStream().use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
            } }
            return target
        } catch (e: Throwable) { target.delete(); throw e }
    }
}
