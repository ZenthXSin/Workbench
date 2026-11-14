package io.github.zenthxsin.rustwright

import io.github.zenthxsin.rustwright.core.agent.AgentToolRegistry
import io.github.zenthxsin.rustwright.core.codex.CodexPoints
import io.github.zenthxsin.rustwright.core.runtime.*
import io.github.zenthxsin.rustwright.core.shell.EnvironmentPoints
import io.github.zenthxsin.rustwright.core.storage.SecretStore
import io.github.zenthxsin.rustwright.core.workspace.WorkspaceService
import io.github.zenthxsin.rustwright.ui.designsystem.ScreenRegistry
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class WorkbenchAssemblyTest {
    @TempDir lateinit var root: File

    @Test fun baseStartsAndCreatesOrdinaryProjectsWithNoBusinessClassesOnClasspath() = runBlocking {
        for (name in listOf("io.github.zenthxsin.rustwright.modcraft.api.ModProject", "io.github.zenthxsin.rustwright.map.api.MapService", "io.github.zenthxsin.rustwright.pic.api.PicService"))
            assertFailsWith<ClassNotFoundException> { Class.forName(name) }
        val ctx = RwContext(TestPlatform(root), RwDispatchers(Dispatchers.Unconfined, Dispatchers.IO, Dispatchers.Unconfined))
        ctx.services.provide(ScreenRegistry())
        ctx.services.provide<SecretStore>(object : SecretStore {
            private val values = HashMap<String, String>()
            override fun get(key: String) = values[key]
            override fun put(key: String, value: String) { values[key] = value }
            override fun remove(key: String) { values.remove(key) }
        })
        // Network route probing is independent of assembly; use the real service registration only.
        val agent = object : RwModule {
            override val id = AgentModule.id
            override fun register(ctx: RwContext) = AgentModule.register(ctx)
        }
        val modules = BaseModules.all().map { if (it == AgentModule) agent else it }
        val host = ModuleHost(ctx, modules)
        try {
            host.start()
            val project = ctx.service<WorkspaceService>().create("demo")
            assertEquals("directory", project.type)
            assertFalse(File(project.directory, "mod-info.txt").exists())
            assertNotNull(ctx.services.getOrNull<ProjectContext>())
            assertNotNull(ctx.services.getOrNull<FusedShellProvider>())
            assertTrue(ctx.extensions.all(ShellPoints.PROJECT_ACTIONS).isEmpty())
            assertTrue(ctx.extensions.all(ShellPoints.INSPECTORS).isEmpty())
            assertTrue(ctx.extensions.all(CodexPoints.LIFECYCLE).isEmpty())
            assertTrue(ctx.extensions.all(EnvironmentPoints.ADDONS).isEmpty())
            assertFalse(ctx.service<AgentToolRegistry>().all.any { it.name == "complete_mod" })
            assertTrue(ctx.service<ScreenRegistry>().all.any { it.id == "files" })
        } finally { host.stop() }
    }

    private class TestPlatform(override val dataDir: File) : Platform {
        override val cacheDir = File(dataDir, "cache").apply { mkdirs() }
        override val appVersion = "test"
        override val appVersionCode = 1
        override fun openUri(uri: String) {}
        override fun shareFile(file: File, mimeType: String) {}
        override fun clipboardText(): String? = null
        override fun setClipboardText(text: String) {}
        override fun toast(message: String) {}
        override suspend fun pickFile(mimeTypes: List<String>): File? = null
        override suspend fun exportFile(file: File, suggestedName: String, mimeType: String) = false
        override fun installUpdate(apk: File, expectedVersionCode: Int) {}
        override fun openAsset(path: String): java.io.InputStream? = null
        override fun listAssets(dir: String) = emptyList<String>()
        override fun persistentDispatcher(foreground: CoroutineDispatcher) = Dispatchers.Unconfined
    }
}
