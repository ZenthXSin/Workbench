package io.github.zenthxsin.rustwright.core.editor.api
import java.io.File
abstract class FileDraftStore {
    abstract fun open(root: File, path: String): FileDraft
    abstract fun retain(draft: FileDraft, text: String)
    abstract fun discard(draft: FileDraft)
    abstract fun save(draft: FileDraft, text: String): FileDraft
}
data class FileDraft(val root: File, val path: String, val baseline: String, val text: String) {
    val dirty get() = text != baseline
}
