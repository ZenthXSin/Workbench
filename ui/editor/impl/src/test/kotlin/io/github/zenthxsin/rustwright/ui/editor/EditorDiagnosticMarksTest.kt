package io.github.zenthxsin.rustwright.ui.editor

import io.github.zenthxsin.rustwright.ui.editor.api.EditorDiagnostic
import io.github.zenthxsin.rustwright.ui.widgets.code.CodeEditor
import kotlin.test.*

class EditorDiagnosticMarksTest {
    @Test fun marksChooseTheKeyOrValueAndPreserveExplicitColumns() {
        val lines = listOf("  enabled: invalid value  ")
        val key = EditorDiagnostic("warn", 1, "重复字段", key = "enabled")
        assertEquals(CodeEditor.Mark(0, 2, 9, 1), EditorDiagnosticMarks.of(key, lines))
        val value = key.copy(severity = "error", code = "invalid-type")
        assertEquals(CodeEditor.Mark(0, 11, 24, 2), EditorDiagnosticMarks.of(value, lines))
        assertEquals(CodeEditor.Mark(0, 19, 24, 2), EditorDiagnosticMarks.of(value.copy(column = 20), lines))
        assertNull(EditorDiagnosticMarks.of(key.copy(line = 10), lines))
        assertEquals(CodeEditor.Mark(0, 0, 1, 1), EditorDiagnosticMarks.of(key, listOf("   ")))
    }
}
