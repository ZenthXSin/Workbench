package io.github.zenthxsin.rustwright.core.workspace

import io.github.zenthxsin.rustwright.core.runtime.Extensions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class WorkspaceServiceTest {
    @TempDir lateinit var root: File
    private fun archive(name: String, entries: Map<String, String>): File = File(root, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, text) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }

    @Test fun ordinaryProjectsWorkWithoutBusinessPlugins() = runBlocking {
        val service = WorkspaceService(Extensions(), root)
        val project = service.create("普通项目")
        assertEquals("directory", project.type)
        assertTrue(project.directory.isDirectory)
        assertFalse(File(project.directory, "mod-info.txt").exists())
        File(project.directory, "readme.md").writeText("hello")
        service.rename(project.directory, "新标题")
        assertEquals("hello", File(project.directory, "readme.md").readText())
        assertEquals(listOf(project), service.projects())
        service.delete(project.directory)
        assertTrue(service.projects().isEmpty())
    }

    @Test fun archiveImportFlattensOneRootAndNeverOverwritesAnExistingProject() = runBlocking {
        val service = WorkspaceService(Extensions(), root)
        val zip = archive("source.zip", mapOf("demo/src/main.kt" to "hello", "demo/readme.md" to "readme"))
        val first = service.importArchive(zip)
        val second = service.importArchive(zip)
        assertEquals("demo", first.name)
        assertEquals("demo (2)", second.name)
        assertEquals("hello", File(first.directory, "src/main.kt").readText())
        assertFalse(File(first.directory, "mod-info.txt").exists())
    }

    @Test fun invalidArchiveAndFailedPluginImportLeaveNoPartialProject() = runBlocking {
        val extensions = Extensions()
        val service = WorkspaceService(extensions, root)
        val bad = archive("escape.zip", mapOf("../../escape" to "bad"))
        assertFailsWith<IllegalArgumentException> { service.importArchive(bad) }
        assertTrue(service.projects().isEmpty())
        val pluginRoot = File(root, "custom")
        extensions.add(WorkspacePoints.TYPES, object : ProjectType {
            override val id = "custom"
            override val title = "Custom"
            override val order = 0
            override val root = pluginRoot
            override suspend fun create(name: String) = File(root, name).apply { mkdirs() }
            override suspend fun imported(directory: File) { error("plugin import rejected") }
        })
        assertFailsWith<IllegalStateException> { service.importArchive(archive("valid.zip", mapOf("readme.md" to "ok")), "custom") }
        assertTrue(pluginRoot.listFiles().orEmpty().isEmpty())
    }

    @Test fun projectTypesAreRegisteredAndRemovedWithoutAFrameworkEnum() = runBlocking {
        val extensions = Extensions()
        val pluginRoot = File(root, "plugin-projects")
        var renamed = ""
        val handle = extensions.add(WorkspacePoints.TYPES, object : ProjectType {
            override val id = "custom"
            override val title = "Custom"
            override val order = 0
            override val root = pluginRoot
            override suspend fun create(name: String) = File(root, name).apply { mkdirs() }
            override suspend fun renamed(directory: File, title: String) { renamed = title }
        })
        val service = WorkspaceService(extensions, root) { "custom" }
        val project = service.create("demo")
        assertEquals("custom", project.type)
        service.rename(project.directory, "new")
        assertEquals("new", renamed)
        handle.remove()
        assertEquals("directory", service.defaultType())
        assertFailsWith<IllegalStateException> { service.delete(project.directory) }
        assertTrue(project.directory.isDirectory)
    }

    @Test fun creationAndDeletionCannotEscapeManagedRoots() = runBlocking {
        val service = WorkspaceService(Extensions(), root)
        for (name in listOf("..", "../outside", "/absolute", "hidden/.project", ".hidden", "bad\nname"))
            assertFailsWith<IllegalArgumentException> { service.create(name) }
        val external = File(root, "external").apply { mkdirs() }
        assertFailsWith<IllegalStateException> { service.delete(external) }
        assertTrue(external.isDirectory)
    }
}
