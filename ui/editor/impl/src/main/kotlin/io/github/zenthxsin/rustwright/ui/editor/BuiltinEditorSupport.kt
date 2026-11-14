package io.github.zenthxsin.rustwright.ui.editor

import arc.graphics.Color
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.editor.api.*
import io.github.zenthxsin.rustwright.ui.kit.*
import io.github.zenthxsin.rustwright.ui.widgets.IniHighlighter
import io.github.zenthxsin.rustwright.ui.widgets.code.*
import io.github.zenthxsin.rustwright.core.editor.format.TextFormatter
import java.io.File

/** Default language support. Domain plugins can replace each capability independently. */
class BuiltinEditorSupport : EditorSupport() {
    override val id = "text"
    override fun commentPrefix(file: File) = if (config(file) || file.extension.lowercase() in setOf("py", "sh", "yaml", "yml", "rb", "pl")) "# " else if (file.extension.lowercase() == "lua") "-- " else "// "
    override fun accepts(file: File) = TextFiles.isText(file)
    private fun config(file: File) = file.extension.lowercase() in setOf("ini", "cfg", "conf", "properties", "toml", "template")
    override fun highlighter(file: File): LineHighlighter? = if (config(file)) IniLineHighlighter else GenericCodeHighlighter
    override fun color(token: Byte): Color = when (token.toInt()) {
        GenericCodeTokens.STRING -> K.dl("88B98A", "367A3E"); GenericCodeTokens.COMMENT -> K.dl("7E8790", "788078")
        GenericCodeTokens.KEYWORD -> K.dl("BE9BD6", "8057A0"); GenericCodeTokens.NUMBER -> K.dl("D6A36B", "A06B2A")
        else -> IniHighlighter.color(token)
    }
    override fun format(file: File, text: String) = TextFormatter.format(text, config(file))
    override fun assistance(host: EditorHost, tip: Table) = WordEditorAssistance(host.editor, tip, host.scope)
    override fun outline(file: File, lines: List<String>) = TextOutline.parse(lines, config(file))
    override suspend fun problems(root: File, file: File, text: String) =
        if (config(file)) ConfigurationDiagnostics.check(text) else emptyList()
    override fun configurationFields(file: File) = config(file)
    override fun controls(field: EditorField) =
        BuiltinFieldControls.build(field, isBoolean(field.key, field.section, field.value))
}
