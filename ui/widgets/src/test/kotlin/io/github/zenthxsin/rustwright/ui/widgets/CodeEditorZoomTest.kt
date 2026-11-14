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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeEditorZoomTest {
    @Test fun thousandthScaleSurvivesGestureEnd() {
        val requested = CodeEditor.DEFAULT_ZOOM / 1000f
        val committed = CodeEditor.normalizeZoom(requested)
        assertTrue(committed > 0f)
        assertEquals(0.001f, committed / CodeEditor.DEFAULT_ZOOM, 0.0000001f)
        assertEquals(committed, CodeEditor.normalizeZoom(committed))
    }

    @Test fun repeatedPinchesCanReachAndLeaveTheMinimum() {
        var size = CodeEditor.DEFAULT_ZOOM
        repeat(20) { size = CodeEditor.normalizeZoom(size / 2f) }
        assertEquals(CodeEditor.MIN_ZOOM, size)
        size = CodeEditor.normalizeZoom(size * 2f)
        assertTrue(size > CodeEditor.MIN_ZOOM)
    }

    @Test fun smallSizesDoNotSnapToHalfPointSteps() {
        for (size in listOf(0.02f, 0.12f, 0.49f, 1.13f, 7.99f)) {
            assertEquals(size, CodeEditor.normalizeZoom(size))
        }
    }

    @Test fun normalSizeStepsAndUpperLimitRemain() {
        assertEquals(13f, CodeEditor.normalizeZoom(13.24f))
        assertEquals(13.5f, CodeEditor.normalizeZoom(13.26f))
        assertEquals(CodeEditor.MAX_ZOOM, CodeEditor.normalizeZoom(100f))
        assertEquals(CodeEditor.MIN_ZOOM, CodeEditor.normalizeZoom(0f))
    }

    @Test fun tinyFontSurvivesReopeningAndAnotherEditorsZoom() {
        val oldApp = Core.app; val oldGraphics = Core.graphics; val oldScene = Core.scene
        val oldGl = Core.gl; val oldGl20 = Core.gl20; val oldInput = Core.input
        val oldZoom = CodeEditor.zoom
        val fontField = RwTheme::class.java.getDeclaredField("font").apply { isAccessible = true }
        val oldFont = fontField.get(null)
        val cacheField = RwTheme::class.java.getDeclaredField("fontCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val cache = cacheField.get(null) as MutableMap<String, Font>
        val oldCache = HashMap(cache)
        try {
            Core.app = MockApplication(); Core.graphics = MockGraphics(); Core.input = MockInput()
            Core.gl = MockGL20(); Core.gl20 = Core.gl; Core.scene = Scene()
            fun font(size: Float) = Font(Font.FontData().apply {
                lineHeight = Scl.sp(size * 20f / 13f)
                cssLineHeight = lineHeight; cssAscent = Scl.sp(size)
            }, TextureRegion(), false)
            CodeEditor.zoom = CodeEditor.DEFAULT_ZOOM
            val normalFont = font(13f)
            fontField.set(null, normalFont)
            cache["m13.0/20.0"] = normalFont
            cache["mm14.0"] = font(14f)
            val first = CodeEditor(CodeDocument("name: test"), null) { Color.white }
            first.fontSize = CodeEditor.MIN_ZOOM
            val tinyHeight = first.font.lineHeight
            assertTrue(first.font.data.scaleX > 0f)
            assertEquals(Scl.sp(0.02f), tinyHeight, 0.000001f)
            val second = CodeEditor(CodeDocument(), null) { Color.white }
            assertEquals(CodeEditor.MIN_ZOOM, second.fontSize)
            second.fontSize = 1f
            assertTrue(second.font.lineHeight > tinyHeight)
            assertEquals(tinyHeight, first.font.lineHeight, 0.000001f)
            assertEquals(Scl.sp(20f / 13f), second.font.lineHeight, 0.000001f)
        } finally {
            CodeEditor.zoom = oldZoom
            fontField.set(null, oldFont)
            cache.clear(); cache.putAll(oldCache)
            Core.app = oldApp; Core.graphics = oldGraphics; Core.scene = oldScene
            Core.gl = oldGl; Core.gl20 = oldGl20; Core.input = oldInput
        }
    }
}
