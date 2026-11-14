package io.github.zenthxsin.rustwright.ui.widgets

import io.github.zenthxsin.rustwright.ui.widgets.code.CodeDocument
import io.github.zenthxsin.rustwright.ui.widgets.code.Edit
import io.github.zenthxsin.rustwright.ui.widgets.code.IniLineHighlighter
import io.github.zenthxsin.rustwright.ui.widgets.code.LineHighlights
import io.github.zenthxsin.rustwright.ui.widgets.code.Pos
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class IncrementalHighlightTest {
    /** 随机编辑后，增量着色必须和整篇重新着色一致。 */
    @Test fun incrementalHighlightMatchesFull() {
        val rnd = Random(7)
        val pieces = listOf("[core]", "\n", "name: x", "\"\"\"", "# c", "maxHp: 10", "true", " ", "@memory a:1", ":")
        val d = CodeDocument("[core]\nname: a\ntext: \"\"\"\nhello\n\"\"\"\nmaxHp: 5")
        val h = LineHighlights(d, IniLineHighlighter)
        repeat(400) {
            val a = d.posOf(rnd.nextInt(d.text.length + 1))
            val b = if (rnd.nextInt(3) == 0) d.posOf(rnd.nextInt(d.text.length + 1)) else a
            d.replace(a, b, pieces.random(rnd), Edit.Origin.KEY)
            // 只取一部分行（模拟只看可见区），偶尔全取
            val probe = if (rnd.nextInt(4) == 0) (0 until d.lineCount) else listOf(rnd.nextInt(d.lineCount))
            val full = IniHighlighter.classify(d.text)
            for (l in probe) {
                val off = d.offsetOf(Pos(l, 0))
                val got = h.kindsOf(l).copyOf(d.line(l).length).toList()
                assertEquals(full.copyOfRange(off, off + d.line(l).length).toList(), got, "line $l of:\n${d.text}")
            }
        }
    }
}
