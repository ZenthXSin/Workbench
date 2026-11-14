package io.github.zenthxsin.rustwright.pic.imaging

import kotlin.test.*

class PixelTimelineTest {
    @Test
    fun paletteReorderClearAndDuplicatePreserveActiveIdentity() {
        val d = LayeredPixelDocument(1, 1)
        d.apply(
            listOf(
                LayeredPixelDocument.Action("palette_add", color = Color.WHITE),
                LayeredPixelDocument.Action("duplicate_palette", index = 0),
                LayeredPixelDocument.Action("rename_palette", index = 1, name = "copy"),
                LayeredPixelDocument.Action("move_palette", index = 1, target = 0),
            )
        )
        assertEquals(0, d.activePalette)
        assertEquals("copy", d.palettes[0].name)
        d.apply(listOf(LayeredPixelDocument.Action("clear_palette", index = 0)))
        assertTrue(d.palettes[0].colors.isEmpty())
        assertEquals(Color.WHITE, d.palettes[1].colors.single().color)
        d.apply(listOf(LayeredPixelDocument.Action("undo")))
        assertEquals(Color.WHITE, d.palettes[0].colors.single().color)
    }

    @Test
    fun gutterIsPreservedClonedAndCountedWhileCanvasChanges() {
        val d = LayeredPixelDocument(1, 1)
        val gutter = IntArray(9)
        gutter[0] = Color.WHITE
        d.restore(
            LayeredPixelDocument.Snapshot(
                listOf(LayeredPixelDocument.Layer("old", intArrayOf(0), gutterPixels = gutter)),
                0,
                Color.BLACK,
                null,
            )
        )
        assertEquals(10L, d.retainedPixels)
        d.apply(
            listOf(LayeredPixelDocument.Action("pencil", listOf(LayeredPixelDocument.Point(0, 0))))
        )
        assertEquals(Color.BLACK, d.composite().pixels[0])
        assertEquals(Color.WHITE, d.layers[0].gutterPixels!![0])
        val view = d.layers
        view[0].gutterPixels!![0] = 0
        assertEquals(Color.WHITE, d.layers[0].gutterPixels!![0])
        d.apply(listOf(LayeredPixelDocument.Action("add_layer")))
        assertFailsWith<IllegalArgumentException> {
            d.apply(listOf(LayeredPixelDocument.Action("merge_layer_down", index = 1)))
        }
        assertEquals(Color.WHITE, d.layers[0].gutterPixels!![0])
    }

    @Test
    fun framesAreIndependentOrderedAndUndoable() {
        val doc = LayeredPixelDocument(2, 2)
        doc.apply(
            listOf(
                LayeredPixelDocument.Action("set_color", color = Color.WHITE),
                LayeredPixelDocument.Action("pencil", listOf(LayeredPixelDocument.Point(0, 0))),
            )
        )
        doc.apply(
            listOf(
                LayeredPixelDocument.Action("duplicate_frame", index = 0),
                LayeredPixelDocument.Action("set_frame_duration", index = 1, durationMillis = 250),
                LayeredPixelDocument.Action("set_color", color = Color.BLACK),
                LayeredPixelDocument.Action("pencil", listOf(LayeredPixelDocument.Point(0, 0))),
            )
        )
        assertEquals(Color.WHITE, doc.compositeFrame(0).pixels[0])
        assertEquals(Color.BLACK, doc.compositeFrame(1).pixels[0])
        assertEquals(listOf(100, 250), doc.frameDurations)
        doc.apply(listOf(LayeredPixelDocument.Action("move_frame", index = 1, target = 0)))
        assertEquals(listOf(250, 100), doc.frameDurations)
        assertEquals(0, doc.currentFrame)
        doc.apply(listOf(LayeredPixelDocument.Action("undo")))
        assertEquals(listOf(100, 250), doc.frameDurations)
        doc.apply(listOf(LayeredPixelDocument.Action("redo")))
        assertEquals(listOf(250, 100), doc.frameDurations)
        val snapshot = doc.snapshot()
        val other = LayeredPixelDocument(2, 2)
        other.restore(snapshot)
        assertEquals(listOf(250, 100), other.frameDurations)
        assertEquals(Color.BLACK, other.compositeFrame(0).pixels[0])
    }

    @Test
    fun paletteLibraryEditsDoNotModifyPixels() {
        val d = LayeredPixelDocument(1, 1)
        d.apply(
            listOf(
                LayeredPixelDocument.Action("add_palette", name = "Team colors"),
                LayeredPixelDocument.Action("palette_add", color = Color.WHITE),
                LayeredPixelDocument.Action("palette_add", color = Color.BLACK),
                LayeredPixelDocument.Action("palette_name", index = 0, name = "Highlight"),
            )
        )
        assertEquals(1, d.activePalette)
        assertEquals("Highlight", d.palettes[1].colors[0].name)
        d.apply(listOf(LayeredPixelDocument.Action("palette_sort")))
        assertEquals(Color.BLACK, d.palettes[1].colors[0].color)
        assertEquals(0, d.composite().pixels[0])
        assertFailsWith<IllegalArgumentException> {
            d.apply(listOf(LayeredPixelDocument.Action("palette_edit", index = 9)))
        }
    }

    @Test
    fun allBlendModesHandleTransparentBottomAndOpacity() {
        for (mode in PixelBlend.entries) {
            assertEquals(0x80ff0000.toInt(), mode.composite(0, 0xffff0000.toInt(), 128), mode.name)
            assertEquals(Color.WHITE, mode.composite(Color.WHITE, Color.BLACK, 0), mode.name)
        }
        assertEquals(Color.BLACK, PixelBlend.MULTIPLY.composite(Color.WHITE, Color.BLACK))
        assertEquals(Color.WHITE, PixelBlend.SCREEN.composite(Color.WHITE, Color.BLACK))
        assertEquals(Color.WHITE, PixelBlend.DIFFERENCE.composite(Color.WHITE, Color.BLACK))
    }

    @Test
    fun layerMergeAndFrameCopyRespectBudgetsAndOwnership() {
        val d = LayeredPixelDocument(2, 1)
        d.apply(
            listOf(
                LayeredPixelDocument.Action("set_color", color = Color.WHITE),
                LayeredPixelDocument.Action("pencil", listOf(LayeredPixelDocument.Point(0, 0))),
                LayeredPixelDocument.Action("add_layer"),
                LayeredPixelDocument.Action("set_color", color = Color.BLACK),
                LayeredPixelDocument.Action("pencil", listOf(LayeredPixelDocument.Point(1, 0))),
            )
        )
        val before = d.composite().pixels
        d.apply(listOf(LayeredPixelDocument.Action("merge_layer_down", index = 1)))
        assertEquals(1, d.layers.size)
        assertContentEquals(before, d.composite().pixels)
        d.apply(
            listOf(
                LayeredPixelDocument.Action("add_frame"),
                LayeredPixelDocument.Action("select_frame", index = 0),
                LayeredPixelDocument.Action(
                    "duplicate_layer_to_frames",
                    index = 0,
                    targets = listOf(1),
                ),
            )
        )
        assertContentEquals(before, d.compositeFrame(1).pixels)
        d.apply(listOf(LayeredPixelDocument.Action("nudge_layer", index = 0, dx = 1)))
        assertContentEquals(before, d.compositeFrame(1).pixels)
    }
}
