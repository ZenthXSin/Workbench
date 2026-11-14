package io.github.zenthxsin.rustwright.core.runtime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExtensionsTest {
    private data class Item(override val id: String, override val order: Int = 0, val label: String = id) : Contribution

    private val point = ExtensionPoint<Item>("test.items")

    @Test
    fun sortsByOrderThenRegistration() {
        val ext = Extensions()
        ext.add(point, Item("b", 10))
        ext.add(point, Item("a", 10))
        ext.add(point, Item("first", -1))
        assertEquals(listOf("first", "b", "a"), ext.all(point).map { it.id })
    }

    @Test
    fun laterRegistrationOverridesSameId() {
        val ext = Extensions()
        val builtin = ext.withOwner("core") { ext.add(point, Item("x", label = "内置")) }
        ext.withOwner("project") { ext.add(point, Item("x", label = "项目")) }
        assertEquals(listOf("项目"), ext.all(point).map { it.label })
        assertEquals("project", ext.ownerOf(point, "x"))
        // 被覆盖的旧句柄不能把新项撤掉
        builtin.remove()
        assertEquals("项目", ext.get(point, "x")?.label)
    }

    @Test
    fun removeOwnerAndHandles() {
        val ext = Extensions()
        val flow = ext.changes(point)
        ext.withOwner("pic") { ext.add(point, Item("pic.a")); ext.add(point, Item("pic.b")) }
        val h = ext.withOwner("map") { ext.add(point, Item("map.a")) }
        assertEquals(3, flow.value.size)
        ext.removeOwner("pic")
        assertEquals(listOf("map.a"), flow.value.map { it.id })
        h.remove()
        assertEquals(emptyList(), flow.value)
        assertNull(ext.get(point, "map.a"))
    }

    @Test
    fun moduleHostTracksOwners() {
        val ctx = RwContext(TestPlatform, RwDispatchers(kotlinx.coroutines.Dispatchers.Unconfined, kotlinx.coroutines.Dispatchers.Unconfined, kotlinx.coroutines.Dispatchers.Unconfined))
        val mod = object : RwModule {
            override val id = "demo"
            override fun init(ctx: RwContext) { ctx.extensions.add(point, Item("demo.page")) }
        }
        val host = ModuleHost(ctx, listOf(mod))
        host.start()
        assertEquals("demo", ctx.extensions.ownerOf(point, "demo.page"))
        host.stop()
        assertEquals(emptyList(), ctx.extensions.all(point))
    }

    private object TestPlatform : Platform {
        override val dataDir = java.io.File("build/tmp/data")
        override val cacheDir = java.io.File("build/tmp/cache")
        override val appVersion = "test"
        override fun openUri(uri: String) {}
        override fun shareFile(file: File, mimeType: String) {}
        override fun clipboardText(): String? = null
        override fun setClipboardText(text: String) {}
        override fun toast(message: String) {}
        override suspend fun pickFile(mimeTypes: List<String>): File? = null
        override suspend fun exportFile(file: File, suggestedName: String, mimeType: String) = false
        override fun installUpdate(apk: File, expectedVersionCode: Int) {}
        override val appVersionCode = 1
        override fun openAsset(path: String): java.io.InputStream? = null
        override fun listAssets(dir: String): List<String> = emptyList()
    }
}
