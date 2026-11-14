package io.github.zenthxsin.rustwright

import io.github.zenthxsin.rustwright.core.agent.*
import io.github.zenthxsin.rustwright.core.runtime.*
import io.github.zenthxsin.rustwright.core.storage.SecretStore
import io.github.zenthxsin.rustwright.pic.api.*
import io.github.zenthxsin.rustwright.ui.designsystem.ScreenRegistry
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class ImageAssemblyTest {
    @TempDir lateinit var root: File

    @Test fun genericApplicationIncludesImageToolsWithoutBusinessScenes() = checkAssembly(null)
    @Test fun domainsContributeScenesAndGuidanceWithoutChangingImageImplementation() =
        checkAssembly(PicDomain("example.graphics", listOf("example.scene"), "Example domain guidance", "Example domain instruction"))

    private fun checkAssembly(domain: PicDomain?) = runBlocking {
        assertFailsWith<ClassNotFoundException> { Class.forName("io.github.zenthxsin.rustwright.RwPlugin") }
        val ctx = RwContext(TestPlatform(root), RwDispatchers(Dispatchers.Unconfined, Dispatchers.IO, Dispatchers.Unconfined))
        ctx.services.provide(ScreenRegistry())
        ctx.services.provide<SecretStore>(object : SecretStore {
            private val values = HashMap<String, String>()
            override fun get(key: String) = values[key]
            override fun put(key: String, value: String) { values[key] = value }
            override fun remove(key: String) { values.remove(key) }
        })
        domain?.let { ctx.extensions.add(PicPoints.DOMAINS, it) }
        val agent = object : RwModule {
            override val id = AgentModule.id
            override fun register(ctx: RwContext) = AgentModule.register(ctx)
        }
        val host = ModuleHost(ctx, WorkbenchModules.all().map { if (it == AgentModule) agent else it })
        try {
            host.start()
            assertNotNull(ctx.services.getOrNull<PicService>())
            val tools = ctx.service<AgentToolRegistry>()
            assertTrue(tools.toolsFor(AgentScene("pic")).any { it.name == "generate_image" })
            assertFalse(tools.toolsFor(AgentScene("modcraft")).any { tools.toolsetOf(it.name) == "rustwright-pic" })
            val prompt = ctx.service<AgentPersona>().promptFor(AgentScene("pic"))
            assertFalse(prompt.contains("铁锈战争"))
            val instructions = ctx.extensions.all(AgentPoints.INSTRUCTIONS)
            assertFalse(instructions.any { it.text.contains("built-in RW") })
            if (domain != null) {
                assertTrue(tools.toolsFor(AgentScene("example.scene")).any { it.name == "generate_image" })
                assertTrue(prompt.contains(domain.generationGuidance))
                assertTrue(instructions.any { it.text == domain.instructions })
            }
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
