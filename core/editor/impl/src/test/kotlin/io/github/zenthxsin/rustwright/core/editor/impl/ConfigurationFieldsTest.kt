package io.github.zenthxsin.rustwright.core.editor.impl

import io.github.zenthxsin.rustwright.ui.widgets.code.CodeDocument
import io.github.zenthxsin.rustwright.ui.widgets.code.Edit
import kotlin.test.*

class ConfigurationFieldsTest {
    private fun fields(document: CodeDocument) = ConfigurationFields(document) { from, to, text ->
        document.replace(from, to, text, Edit.Origin.PROGRAM)
    }

    @Test fun editingAValuePreservesSeparatorIndentationAndUndo() {
        val document = CodeDocument("[settings]\n  enabled= true  \n# ignored: false")
        val fields = fields(document)
        assertEquals("settings", fields.sectionAt(1))
        assertNull(fields.keyAt(2))
        fields.setValue(1, "enabled", "false")
        assertEquals("  enabled= false  ", document.line(1))
        document.undo()
        assertEquals("  enabled= true  ", document.line(1))
        fields.setValue(1, "other", "unchanged")
        assertEquals("  enabled= true  ", document.line(1))
    }

    @Test fun sectionEditsStayWithinTheirSection() {
        val document = CodeDocument("[one]\na: 1\n\n[two]\na: 2")
        val fields = fields(document)
        fields.setField("one", "a", "3")
        fields.setField("one", "new", "4")
        assertEquals("[one]\na: 3\nnew: 4\n\n[two]\na: 2", document.text)
        val unchanged = document.text
        fields.setField("missing", "a", "5")
        fields.setValue(-1, "a", "5")
        fields.setValue(500, "a", "5")
        assertNull(fields.valueSpan(500))
        assertEquals(unchanged, document.text)
        fields.appendSection("three")
        assertTrue(document.text.endsWith("\n\n[three]\n"))
    }
}
