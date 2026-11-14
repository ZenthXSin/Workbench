package io.github.zenthxsin.rustwright.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModuleHostTest {

    private class M(override val id: String, override val dependsOn: List<String> = emptyList(), val log: MutableList<String>) : RwModule {
        override fun register(ctx: RwContext) { log += "reg:$id" }
        override fun init(ctx: RwContext) { log += "init:$id" }
        override fun stop(ctx: RwContext) { log += "stop:$id" }
    }

    private fun ctx() = RwContext(FakePlatform, RwDispatchers(Dispatchers.Unconfined, Dispatchers.IO, Dispatchers.Default))

    @Test
    fun ordersByDependencies() {
        val log = mutableListOf<String>()
        val host = ModuleHost(ctx(), listOf(M("editor", listOf("image", "layout"), log), M("image", listOf("layout"), log), M("layout", log = log)))
        assertEquals(listOf("layout", "image", "editor"), host.modules.map { it.id })
        host.start()
        host.stop()
        assertEquals(
            listOf("reg:layout", "reg:image", "reg:editor", "init:layout", "init:image", "init:editor", "stop:editor", "stop:image", "stop:layout"),
            log,
        )
    }

    @Test
    fun rejectsCycles() {
        val log = mutableListOf<String>()
        val e = assertFailsWith<IllegalStateException> { ModuleHost.sort(listOf(M("a", listOf("b"), log), M("b", listOf("a"), log))) }
        assertTrue(e.message!!.contains("循环依赖"))
    }

    @Test
    fun rejectsMissingDependency() {
        assertFailsWith<IllegalStateException> { ModuleHost.sort(listOf(M("a", listOf("x"), mutableListOf()))) }
    }

    @Test
    fun servicesLazyAndUnique() {
        val s = Services()
        var created = 0
        s.provideLazy<StringBuilder> { created++; StringBuilder("x") }
        assertEquals(0, created)
        assertEquals("x", s.get<StringBuilder>().toString())
        s.get<StringBuilder>()
        assertEquals(1, created)
        assertFailsWith<IllegalStateException> { s.provide(StringBuilder()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun eventsDeliverByType() = runTest(UnconfinedTestDispatcher()) {
        val events = Events()
        val received = mutableListOf<Int>()
        val job = events.on<Int>(this) { received += it }
        events.post("ignored")
        events.post(1)
        events.emit(2)
        job.cancel()
        assertEquals(listOf(1, 2), received)
        val next = async { events.stream<String>().first() }
        events.post("hi")
        assertEquals("hi", next.await())
    }

    private object FakePlatform : Platform {
        override val dataDir = File("build/tmp/data")
        override val cacheDir = File("build/tmp/cache")
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
