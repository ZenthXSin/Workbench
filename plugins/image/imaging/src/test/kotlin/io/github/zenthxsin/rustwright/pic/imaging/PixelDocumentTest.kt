package io.github.zenthxsin.rustwright.pic.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PixelDocumentTest {
    private val red = Color.rgb(255, 0, 0)
    private val blue = Color.rgb(0, 0, 255)

    @Test
    fun drawFillUndoRedo() {
        val doc = PixelDocument(RgbaImage(8, 8))
        doc.beginStroke()
        doc.line(0, 3, 7, 3, red) // 横线把画布分成上下两块
        assertEquals(red, doc.get(5, 3))
        doc.beginStroke()
        doc.fill(0, 0, blue)
        assertEquals(blue, doc.get(7, 2))
        assertEquals(Color.TRANSPARENT, doc.get(0, 4)) // 线下方没被填
        assertTrue(doc.undo())
        assertEquals(Color.TRANSPARENT, doc.get(7, 2))
        assertTrue(doc.redo())
        assertEquals(blue, doc.get(7, 2))
        assertEquals(listOf(blue, red), doc.palette())
    }

    @Test
    fun mirrorAndFlip() {
        val doc = PixelDocument(RgbaImage(6, 2))
        doc.mirrorX = true
        doc.beginStroke()
        doc.plot(1, 0, red)
        assertEquals(red, doc.get(4, 0))
        doc.mirrorX = false
        doc.plot(0, 1, blue)
        doc.flipHorizontal()
        assertEquals(blue, doc.get(5, 1))
        assertFalse(doc.get(0, 1) == blue)
    }
}
