package io.github.zenthxsin.rustwright.core.editor.api

import io.github.zenthxsin.rustwright.ui.widgets.code.Edit
import io.github.zenthxsin.rustwright.ui.widgets.code.Pos

/** Platform-independent document contract. Implementations own storage and undo history. */
abstract class TextDocument {
    abstract val version: Int
    abstract val text: String
    abstract val lineCount: Int
    abstract val canUndo: Boolean
    abstract val canRedo: Boolean
    abstract fun line(index: Int): String
    abstract fun linesSnapshot(): List<String>
    abstract fun addListener(listener: (Edit) -> Unit)
    abstract fun removeListener(listener: (Edit) -> Unit)
    /** Reload and clear history without edit notifications; views reset their caches after this call. */
    abstract fun setText(text: String)
    abstract fun clamp(position: Pos): Pos
    abstract fun end(): Pos
    abstract fun offsetOf(position: Pos): Int
    abstract fun posOf(offset: Int): Pos
    abstract fun textIn(start: Pos, end: Pos): String
    abstract fun replace(start: Pos, end: Pos, text: String, origin: Edit.Origin, cursorBefore: Pos = clamp(start)): Pos
    abstract fun seal()
    abstract fun undo(): Pos?
    abstract fun redo(): Pos?
}
