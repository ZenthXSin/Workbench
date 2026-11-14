package io.github.zenthxsin.rustwright.ui.widgets

import io.github.zenthxsin.rustwright.ui.widgets.IniHighlighter.BLOCK
import io.github.zenthxsin.rustwright.ui.widgets.IniHighlighter.BOOL
import io.github.zenthxsin.rustwright.ui.widgets.IniHighlighter.COMMENT
import io.github.zenthxsin.rustwright.ui.widgets.IniHighlighter.KEY
import io.github.zenthxsin.rustwright.ui.widgets.IniHighlighter.NUMBER
import io.github.zenthxsin.rustwright.ui.widgets.IniHighlighter.SECTION
import kotlin.test.Test
import kotlin.test.assertEquals

class IniHighlighterTest {
    private fun kindAt(text: String, needle: String): Byte = IniHighlighter.classify(text)[text.indexOf(needle)]

    @Test
    fun classifiesLines() {
        val t = "[core]\nname: tank_1\nprice: 250\nisBio: true\n# note\ntext: \"\"\"a\nb: 1\n\"\"\"\nmass: 3"
        assertEquals(SECTION, kindAt(t, "[core]"))
        assertEquals(KEY, kindAt(t, "price"))
        assertEquals(NUMBER, kindAt(t, "250"))
        assertEquals(BOOL, kindAt(t, "true"))
        assertEquals(COMMENT, kindAt(t, "note"))
        assertEquals(BLOCK, kindAt(t, "b: 1"))
        assertEquals(KEY, kindAt(t, "mass"))
        assertEquals(IniHighlighter.TEXT, IniHighlighter.classify(t)[t.indexOf("tank_1") + 5])
    }
}
