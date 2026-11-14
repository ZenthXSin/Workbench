package io.github.zenthxsin.rustwright.ui.editor.api

import arc.graphics.Color
import arc.scene.Element
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import io.github.zenthxsin.rustwright.ui.widgets.code.*
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** Language and domain integrations. The editor owns the document, tabs and lifecycle. */
abstract class EditorSupport {
    open val drafts: io.github.zenthxsin.rustwright.core.editor.api.FileDraftStore? get() = null
    open fun commentPrefix(file: File): String = "# "
    abstract val id: String
    abstract fun accepts(file: File): Boolean
    open fun highlighter(file: File): LineHighlighter? = null
    abstract fun color(token: Byte): Color
    abstract fun format(file: File, text: String): String
    open suspend fun problems(root: File, file: File, text: String): List<EditorDiagnostic> = emptyList()
    open fun searchTemplates(): List<io.github.zenthxsin.rustwright.core.editor.search.EditorRegexTemplate> = emptyList()
    open fun assistance(host: EditorHost, tip: Table): EditorAssistance? = null
    /** Opt into section/key/value editing without hard-coded file types in the UI. */
    open fun configurationFields(file: File): Boolean = false
    open fun controls(field: EditorField): Element? = null
    open fun isBoolean(key: String, section: String, value: String): Boolean = value.lowercase() in setOf("true", "false")
    open fun sections(): List<Pair<String, String>> = listOf("general" to "通用", "settings" to "设置", "section_" to "新段落")
    open fun values(): List<Pair<String, String>> = listOf("true" to "true", "false" to "false", "null" to "null", "0" to "0", "1" to "1", "-1" to "-1", "\"\"" to "\"\"")
    open fun outline(file: File, lines: List<String>): List<OutlineNode> = emptyList()
    open fun sectionLabel(section: String): String = ""
    open fun actions(host: EditorHost): List<EditorAction> = emptyList()
    open fun createActions(host: EditorHost): List<EditorAction> = emptyList()
    open suspend fun invalidate(root: File?) {}
    open suspend fun changed(root: File, file: File, text: String) {}
    open fun designer(host: EditorHost, close: () -> Unit): Element? = null
    open fun closeDesigner(designer: Element) {}
}
interface EditorAssistance {
    val candidates: Table
    val isOpen: Boolean
    fun act()
    fun open()
    fun acceptSelected(): Boolean
    fun dispose()
}
interface EditorHost {
    val editor: CodeEditor
    val file: File?
    val root: File?
    val scope: CoroutineScope
    fun project(): ProjectContext?
    fun openFile(file: File)
    fun invalidate()
    fun openDesigner()
}
data class EditorField(
    val host: EditorHost, val key: String, val value: String, val section: String, val line: Int,
    val setValue: (String) -> Unit, val setField: (String, String) -> Unit,
    val newSection: (String, String) -> Unit, val setValueLive: (String) -> Unit,
)
data class EditorAction(val title: String, val icon: String, val enabled: Boolean = true, val action: () -> Unit)
data class EditorDiagnostic(
    val severity: String, val line: Int, val message: String, val section: String? = null,
    val key: String? = null, val column: Int = 1, val code: String = "",
    val displayMessage: String = message, val codeLabel: String = code,
)
