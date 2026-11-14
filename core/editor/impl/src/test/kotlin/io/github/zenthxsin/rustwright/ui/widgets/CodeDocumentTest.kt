package io.github.zenthxsin.rustwright.ui.widgets

import io.github.zenthxsin.rustwright.ui.widgets.code.CodeDocument
import io.github.zenthxsin.rustwright.ui.widgets.code.Edit
import io.github.zenthxsin.rustwright.ui.widgets.code.Pos
import kotlin.test.Test
import kotlin.test.assertEquals

class CodeDocumentTest {
    @Test fun replaceAcrossLines() {
        val d = CodeDocument("[core]\nname: a\nprice: 1")
        val end = d.replace(Pos(1, 6), Pos(2, 5), "b\nmass: 2\ncost", Edit.Origin.PROGRAM)
        assertEquals("[core]\nname: b\nmass: 2\ncost: 1", d.text)
        assertEquals(Pos(3, 4), end)
        assertEquals(Pos(2, 3), d.posOf(d.offsetOf(Pos(2, 3))))
    }

    @Test fun undoMergesTyping() {
        val d = CodeDocument("x")
        var c = Pos(0, 1)
        for (ch in "abc") c = d.replace(c, c, ch.toString(), Edit.Origin.KEY)
        c = d.replace(c, c, "\n", Edit.Origin.KEY)
        assertEquals("xabc\n", d.text)
        d.undo(); assertEquals("xabc", d.text)
        assertEquals(Pos(0, 1), d.undo()); assertEquals("x", d.text)
        d.redo(); d.redo(); assertEquals("xabc\n", d.text)
    }

}
