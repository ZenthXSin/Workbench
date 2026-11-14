package io.github.zenthxsin.rustwright.ui.editor.api
/** Zero-based document positions. Children form a foldable outline. */
data class OutlineNode(val name: String, val line: Int, val value: String = "", val children: List<OutlineNode> = emptyList())
