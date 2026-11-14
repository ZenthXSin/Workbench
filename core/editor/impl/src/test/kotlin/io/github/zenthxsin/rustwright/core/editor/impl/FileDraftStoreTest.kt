package io.github.zenthxsin.rustwright.core.editor.impl
import io.github.zenthxsin.rustwright.core.editor.api.FileDraftStore
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import java.io.File
class FileDraftStoreTest {
    @TempDir lateinit var root: File
    @Test fun abstractionRetainsDraftsAndAtomicSaveClearsThem() {
        val store: FileDraftStore = MemoryFileDraftStore(); val f = File(root, "notes.txt"); f.writeText("before")
        val d = store.open(root, f.name); store.retain(d, "after")
        assertEquals("after", store.open(root, f.name).text); assertEquals("before", f.readText())
        val saved = store.save(d, "after"); assertFalse(saved.dirty); assertEquals("after", f.readText())
        assertFalse(store.open(root, f.name).dirty)
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".editor-") })
    }
    @Test fun externalChangesAreNeverOverwrittenAndDraftIsRetained() {
        val store = MemoryFileDraftStore(); val f = File(root, "notes.txt"); f.writeText("before")
        val d = store.open(root, f.name); store.retain(d, "mine"); f.writeText("external")
        assertFailsWith<IllegalArgumentException> { store.save(d, "mine") }
        assertEquals("external", f.readText()); assertEquals("mine", store.open(root, f.name).text)
        store.discard(d); assertEquals("external", store.open(root, f.name).text)
    }
    @Test fun traversalAndSymlinkEscapeAreRejected() {
        val store = MemoryFileDraftStore()
        assertFailsWith<IllegalArgumentException> { store.open(root, "../escape") }
        assertFailsWith<IllegalArgumentException> { store.open(root, root.absolutePath) }
        val outside = File.createTempFile("draft-outside", ".txt")
        try {
            java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
            assertFailsWith<IllegalArgumentException> { store.open(root, "link") }
        } finally { outside.delete() }
    }
}
