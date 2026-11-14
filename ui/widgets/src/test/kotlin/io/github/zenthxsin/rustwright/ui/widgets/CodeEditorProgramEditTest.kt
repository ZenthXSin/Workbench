package io.github.zenthxsin.rustwright.ui.widgets

import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Font
import arc.graphics.g2d.TextureRegion
import arc.mock.MockApplication
import arc.mock.MockGL20
import arc.mock.MockGraphics
import arc.mock.MockInput
import arc.scene.Scene
import arc.scene.ui.layout.Scl
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.widgets.code.CodeDocument
import io.github.zenthxsin.rustwright.ui.widgets.code.CodeEditor
import io.github.zenthxsin.rustwright.ui.widgets.code.Pos
import kotlin.test.Test
import kotlin.test.assertEquals

/** 程序改字（字段控件 / 注释 / 格式化）后光标要跟着平移，不能越过行尾（越界会在下一帧画高亮时崩）。 */
class CodeEditorProgramEditTest {
    private fun <T> withEditor(text: String, block: (CodeEditor) -> T): T {
        val oldApp = Core.app; val oldGraphics = Core.graphics; val oldScene = Core.scene
        val oldGl = Core.gl; val oldGl20 = Core.gl20; val oldInput = Core.input
        val fontField = RwTheme::class.java.getDeclaredField("font").apply { isAccessible = true }
        val oldFont = fontField.get(null)
        val cacheField = RwTheme::class.java.getDeclaredField("fontCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val cache = cacheField.get(null) as MutableMap<String, Font>
        val oldCache = HashMap(cache)
        try {
            Core.app = MockApplication(); Core.graphics = MockGraphics(); Core.input = MockInput()
            Core.gl = MockGL20(); Core.gl20 = Core.gl; Core.scene = Scene()
            fun font(size: Float) = Font(Font.FontData().apply { lineHeight = Scl.sp(size * 20f / 13f); cssLineHeight = lineHeight; cssAscent = Scl.sp(size) }, TextureRegion(), false)
            val f = font(13f); fontField.set(null, f); cache["m13.0/20.0"] = f; cache["mm14.0"] = font(14f)
            return block(CodeEditor(CodeDocument(text), null) { Color.white })
        } finally {
            fontField.set(null, oldFont); cache.clear(); cache.putAll(oldCache)
            Core.app = oldApp; Core.graphics = oldGraphics; Core.scene = oldScene
            Core.gl = oldGl; Core.gl20 = oldGl20; Core.input = oldInput
        }
    }

    @Test fun switchingLanguagesResetsInactiveHighlightersBeforeEditing() = withEditor("notes") { e ->
        val ini = io.github.zenthxsin.rustwright.ui.widgets.code.IniLineHighlighter
        val text = object : io.github.zenthxsin.rustwright.ui.widgets.code.LineHighlighter {
            override fun highlight(line: String, state: Int, out: ByteArray): Int { out.fill(0); return 0 }
        }
        e.setHighlighter(ini); e.setText("[settings]\na: 1\nb: 2")
        e.setHighlighter(text); e.setText("notes")
        e.setHighlighter(ini); e.setText("[settings]\na: 1\nb: 2")
        e.setCursor(e.doc.end()); e.insert("x")
        assertEquals("b: 2x", e.doc.line(2))
        e.setHighlighter(text); e.setText("one\ntwo\nthree\nfour")
        e.setCursor(e.doc.end()); e.insert("!")
        assertEquals("four!", e.doc.line(3))
    }

    @Test fun uncommentAtLineEndKeepsCursorInsideLine() = withEditor("# canAttack: false\nx: 1") { e ->
        e.setCursor(Pos(0, 18))
        e.replace(Pos(0, 0), Pos(0, 2), "")
        assertEquals(Pos(0, 16), e.cursor)
    }

    @Test fun shorterValueBeforeCursorShiftsIt() = withEditor("canAttack: false # note") { e ->
        e.setCursor(Pos(0, 23))
        e.replace(Pos(0, 11), Pos(0, 16), "true")
        assertEquals(Pos(0, 22), e.cursor)
    }

    @Test fun editAfterCursorLeavesItAlone() = withEditor("a: 1\nb: 2") { e ->
        e.setCursor(Pos(0, 1))
        e.replace(Pos(1, 3), Pos(1, 4), "300")
        assertEquals(Pos(0, 1), e.cursor)
    }

    @Test fun insertedLinesAboveMoveCursorDown() = withEditor("[core]\nname: a") { e ->
        e.setCursor(Pos(1, 3))
        e.replace(Pos(0, 6), Pos(0, 6), "\nprice: 1")
        assertEquals(Pos(2, 3), e.cursor)
    }

    @Test fun cursorInsideReplacedRangeGoesToItsEnd() = withEditor("image: old_name.png") { e ->
        e.setCursor(Pos(0, 10))
        e.replace(Pos(0, 7), Pos(0, 19), "a.png")
        assertEquals(Pos(0, 12), e.cursor)
    }

    /** 手机输入法的「换行」走 imeAction（安卓桥拦下回车键事件转过来）：要真的换行并保持缩进。 */
    @Test fun imeActionInsertsNewlineWithIndent() = withEditor("[core]\n  name: a") { e ->
        e.setCursor(Pos(1, 9))
        e.imeAction()
        assertEquals("  name: a", e.doc.line(1))
        assertEquals("  ", e.doc.line(2))
        assertEquals(Pos(2, 2), e.cursor)
    }

    @Test fun imeActionAsksHostFirst() = withEditor("a: 1") { e ->
        var asked = 0
        e.onEnter = { asked++; true }
        e.setCursor(Pos(0, 4))
        e.imeAction()
        assertEquals(1, asked)
        assertEquals(1, e.doc.lineCount)
    }
}
