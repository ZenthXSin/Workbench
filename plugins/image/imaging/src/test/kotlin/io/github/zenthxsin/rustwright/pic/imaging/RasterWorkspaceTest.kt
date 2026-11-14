package io.github.zenthxsin.rustwright.pic.imaging

import kotlin.test.*

class RasterWorkspaceTest {
    @Test
    fun largeDistinctPaletteHasExactCountsWithoutBoxingAllColors() {
        val image = RgbaImage(2048, 2048, IntArray(2048 * 2048) { 0xff000000.toInt() or it })
        val colors = RasterOperations.palette(image, 32)
        assertEquals(32, colors.size)
        assertTrue(colors.all { it.second == 1 })
        val quality = PixelArtQualityAnalyzer.analyze(image)
        assertEquals(4_194_304, quality.colorCount)
        assertFailsWith<IllegalArgumentException> {
            RasterOperations.quantize(image, List(256) { it })
        }
    }

    @Test
    fun cropRotationAndSpriteSheetRoundTrip() {
        val src = RgbaImage(4, 2, IntArray(8) { it + 1 })
        val frames = RasterOperations.split(src, 2, 1)
        assertContentEquals(intArrayOf(1, 2, 5, 6), frames[0].pixels)
        assertContentEquals(src.pixels, RasterOperations.join(frames, 2).pixels)
        assertContentEquals(
            intArrayOf(5, 1, 6, 2, 7, 3, 8, 4),
            RasterOperations.rotate(src, 1).pixels,
        )
        assertFailsWith<IllegalArgumentException> { RasterOperations.split(src, 3, 1) }
        assertFailsWith<IllegalArgumentException> {
            RasterOperations.crop(src, Int.MAX_VALUE, 0, 2, 2)
        }
    }

    @Test
    fun rgbaHexAndAlphaBlendAreUnambiguous() {
        assertEquals(0x80123456.toInt(), RasterOperations.color("#12345680"))
        assertEquals("#12345680", RasterOperations.hex(0x80123456.toInt()))
        assertEquals(0x80ff0000.toInt(), RasterOperations.over(0, 0x80ff0000.toInt()))
        assertEquals(
            0xffff0000.toInt(),
            RasterOperations.over(0xff0000ff.toInt(), 0xffff0000.toInt()),
        )
        assertEquals(0, RasterOperations.over(0, 0))
    }

    @Test
    fun quantizationPreservesAlphaAndComparisonCountsChanges() {
        val src = RgbaImage(2, 1, intArrayOf(0x80f00102.toInt(), 0))
        val out = RasterOperations.quantize(src, listOf(0xffff0000.toInt()))
        assertEquals(0x80ff0000.toInt(), out.pixels[0])
        assertEquals(0, out.pixels[1])
        val d = RasterOperations.compare(src, out)
        assertEquals(1, d.changedPixels)
        assertEquals(15, d.maxChannelError)
    }

    @Test
    fun layeredBatchIsAtomicAndUndoable() {
        val d = LayeredPixelDocument(4, 4)
        d.apply(
            listOf(
                LayeredPixelDocument.Action("set_color", color = 0xffff0000.toInt()),
                LayeredPixelDocument.Action("pencil", listOf(LayeredPixelDocument.Point(1, 1))),
            )
        )
        assertEquals(0xffff0000.toInt(), d.composite().getPixel(1, 1))
        val before = d.composite().pixels.copyOf()
        assertFailsWith<IllegalArgumentException> {
            d.apply(
                listOf(
                    LayeredPixelDocument.Action("add_layer", name = "Top"),
                    LayeredPixelDocument.Action("pencil", listOf(LayeredPixelDocument.Point(99, 1))),
                )
            )
        }
        assertEquals(1, d.layers.size)
        assertContentEquals(before, d.composite().pixels)
        d.apply(listOf(LayeredPixelDocument.Action("undo")))
        assertEquals(0, d.composite().getPixel(1, 1))
        d.apply(listOf(LayeredPixelDocument.Action("redo")))
        assertContentEquals(before, d.composite().pixels)
    }

    @Test
    fun selectionClipsFloodFillAndLayersComposite() {
        val d = LayeredPixelDocument(3, 3)
        d.apply(
            listOf(
                LayeredPixelDocument.Action(
                    "rectangle_selection",
                    listOf(LayeredPixelDocument.Point(0, 0), LayeredPixelDocument.Point(1, 1)),
                ),
                LayeredPixelDocument.Action("set_color", color = 0xffff0000.toInt()),
                LayeredPixelDocument.Action("flood_fill", listOf(LayeredPixelDocument.Point(0, 0))),
            )
        )
        assertEquals(4, d.composite().pixels.count { it != 0 })
        d.apply(
            listOf(
                LayeredPixelDocument.Action("add_layer", name = "Top"),
                LayeredPixelDocument.Action("clear_selection"),
                LayeredPixelDocument.Action("set_color", color = 0xff0000ff.toInt()),
                LayeredPixelDocument.Action("pencil", listOf(LayeredPixelDocument.Point(0, 0))),
            )
        )
        assertEquals(0xff0000ff.toInt(), d.composite().getPixel(0, 0))
        assertEquals(0xffff0000.toInt(), d.layers[0].pixels[0])
    }

    @Test
    fun documentAndRasterBudgetsRejectBeforeAllocation() {
        assertFailsWith<IllegalArgumentException> { LayeredPixelDocument(100_000, 100_000) }
        assertFailsWith<IllegalArgumentException> { RasterOperations.dimensions(Int.MAX_VALUE, 2) }
        val d = LayeredPixelDocument(1024, 1024)
        repeat(3) { d.apply(listOf(LayeredPixelDocument.Action("add_layer"))) }
        assertFailsWith<IllegalArgumentException> {
            d.apply(listOf(LayeredPixelDocument.Action("add_layer")))
        }
        assertEquals(4, d.layers.size)
    }
}
