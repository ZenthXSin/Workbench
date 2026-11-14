package io.github.zenthxsin.rustwright.core.runtime

import java.nio.file.Files
import kotlin.test.*

class ImportedFilesTest {
    @Test fun preservesNamesAndDoesNotOverwriteDuplicateImports() {
        val root = Files.createTempDirectory("imports-test").toFile()
        try {
            val a = root.resolve("a").apply { mkdirs() }.resolve("贴图.png").apply { writeBytes(byteArrayOf(1, 2)) }
            val b = root.resolve("b").apply { mkdirs() }.resolve("贴图.png").apply { writeBytes(byteArrayOf(3, 4)) }
            val dest = root.resolve("project")
            val first = ImportedFiles.copy(a, dest)
            val second = ImportedFiles.copy(b, dest)
            assertEquals("贴图.png", first.name)
            assertEquals("贴图 (2).png", second.name)
            assertContentEquals(byteArrayOf(1, 2), first.readBytes())
            assertContentEquals(byteArrayOf(3, 4), second.readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun failedCopyRemovesPartialFileAndKeepsExistingFiles() {
        val root = Files.createTempDirectory("imports-cancel").toFile()
        try {
            val source = root.resolve("source.bin").apply { writeBytes(ByteArray(128 * 1024)) }
            val dest = root.resolve("project").apply { mkdirs() }
            val existing = dest.resolve("source.bin").apply { writeText("keep") }
            var checks = 0
            assertFailsWith<IllegalStateException> { ImportedFiles.copy(source, dest) { if (++checks == 2) error("cancel") } }
            assertEquals("keep", existing.readText())
            assertEquals(listOf("source.bin"), dest.listFiles()!!.map { it.name })
            assertFailsWith<IllegalArgumentException> { ImportedFiles.available(dest, "../outside") }
        } finally { root.deleteRecursively() }
    }
}
