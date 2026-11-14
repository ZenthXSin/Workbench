package io.github.zenthxsin.rustwright.core.runtime.archive

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class TarArchiveTest {
    private fun entry(name: String, type: Char = '0', link: String = "", content: String = "safe", declaredSize: Long? = null): ByteArray {
        val header = ByteArray(512)
        fun field(offset: Int, value: String) { value.toByteArray().copyInto(header, offset) }
        val bytes = if (type == '0') content.toByteArray() else ByteArray(0)
        field(0, name); field(100, "0000644"); field(124, (declaredSize ?: bytes.size.toLong()).toString(8).padStart(11, '0'))
        header[156] = type.code.toByte(); field(157, link)
        for (i in 148..155) header[i] = 32
        val checksum = header.sumOf { it.toInt() and 255 }
        field(148, checksum.toString(8).padStart(6, '0') + "\u0000 ")
        return ByteArrayOutputStream().apply {
            write(header); write(bytes); write(ByteArray((512 - bytes.size % 512) % 512))
        }.toByteArray()
    }

    @Test fun extractsNormalFileAndRejectsTraversal() {
        val root = Files.createTempDirectory("tar-security-").toFile()
        try {
            TarArchive.extract(entry("units/a.ini").inputStream(), root)
            assertEquals("safe", File(root, "units/a.ini").readText())
            for (path in listOf("../outside", "/outside", "units/../../outside", "..\\outside")) {
                assertFailsWith<IllegalArgumentException> { TarArchive.extract(entry(path).inputStream(), root) }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsEscapingLinksAndRebasesAbsoluteRootfsLinks() {
        val root = Files.createTempDirectory("tar-links-").toFile()
        try {
            assertFailsWith<IllegalArgumentException> { TarArchive.extract(entry("link", '2', "../../outside").inputStream(), root) }
            assertFailsWith<IllegalArgumentException> { TarArchive.extract(entry("hard", '1', "../outside").inputStream(), root) }
            var resolved: File? = null
            TarArchive.extract(entry("absolute", '2', "/etc/file").inputStream(), root,
                symlink = { link, path -> resolved = File(File(path).parentFile, link).canonicalFile })
            assertEquals(File(root, "etc/file").canonicalFile, resolved)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsOversizedTruncatedAndCorruptArchives() {
        val root = Files.createTempDirectory("tar-invalid-").toFile()
        try {
            assertFailsWith<IllegalArgumentException> { TarArchive.extract(entry("huge", declaredSize = 513L * 1024 * 1024).inputStream(), root) }
            assertFailsWith<IllegalStateException> { TarArchive.extract(entry("short").copyOf(514).inputStream(), root) }
            val corrupt = entry("bad").apply { this[0] = 'X'.code.toByte() }
            assertFailsWith<IllegalArgumentException> { TarArchive.extract(corrupt.inputStream(), root) }
        } finally { root.deleteRecursively() }
    }
}
