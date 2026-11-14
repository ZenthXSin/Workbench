package io.github.zenthxsin.rustwright.core.runtime.archive

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ZipArchiveTest {
    @Test fun extractsIntoNewFolderWithoutOverwritingExistingOutput() {
        val root = Files.createTempDirectory("unzip-test").toFile()
        try {
            val zip = root.resolve("模组.zip")
            ZipOutputStream(zip.outputStream()).use {
                it.putNextEntry(ZipEntry("units/单位.ini")); it.write("[core]".toByteArray()); it.closeEntry()
                it.putNextEntry(ZipEntry("units/")); it.closeEntry()
            }
            val first = ZipArchive.extract(zip, root)
            val second = ZipArchive.extract(zip, root)
            assertEquals("模组", first.name)
            assertEquals("模组 (2)", second.name)
            assertEquals("[core]", second.resolve("units/单位.ini").readText())
            assertTrue(zip.isFile)
        } finally { root.deleteRecursively() }
    }
    @Test fun dotRootDirectoryEntryIsSkippedNotRejected() {
        val root = Files.createTempDirectory("unzip-dot").toFile()
        try {
            val zip = root.resolve("dot.zip")
            ZipOutputStream(zip.outputStream()).use {
                it.putNextEntry(ZipEntry("./")); it.closeEntry()
                it.putNextEntry(ZipEntry("./units/a.ini")); it.write("[core]".toByteArray()); it.closeEntry()
            }
            assertEquals("[core]", ZipArchive.extract(zip, root).resolve("units/a.ini").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun traversalAndCancellationPublishNoPartialDirectory() {
        val root = Files.createTempDirectory("unzip-bad").toFile()
        try {
            val zip = root.resolve("bad.zip")
            ZipOutputStream(zip.outputStream()).use {
                it.putNextEntry(ZipEntry("../escape")); it.write(1); it.closeEntry()
            }
            assertFailsWith<IllegalArgumentException> { ZipArchive.extract(zip, root) }
            assertEquals(listOf("bad.zip"), root.listFiles()!!.map { it.name })
            assertFailsWith<IllegalStateException> { ZipArchive.extract(zip, root) { error("cancel") } }
            assertEquals(listOf("bad.zip"), root.listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }
}
