package io.github.zenthxsin.rustwright.pic.imaging

import kotlin.test.*

class RasterFiltersTest {
    @Test
    fun sanitizedNamespaceSurvivesSerializationAndParsing() {
        val svg =
            SvgSanitizer.sanitize(
                "<svg viewBox=\"0 0 10 10\"><g><rect width=\"10\" height=\"10\"/></g></svg>"
            )
        val parser =
            javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
        val root = parser.parse(org.xml.sax.InputSource(java.io.StringReader(svg))).documentElement
        assertEquals("http://www.w3.org/2000/svg", root.namespaceURI)
        assertEquals("http://www.w3.org/2000/svg", root.firstChild.firstChild.namespaceURI)
        assertFailsWith<IllegalArgumentException> {
            SvgSanitizer.sanitize("<svg xmlns=\"urn:other\" viewBox=\"0 0 1 1\"/>")
        }
    }

    @Test
    fun filtersRetainAlphaAndExpectedColorSemantics() {
        val red = RgbaImage(1, 1, intArrayOf(0x80ff0000.toInt()))
        assertEquals(0x8000ff00.toInt(), RasterFilters.adjust(red, "hue", 120.0).pixels[0])
        assertEquals(0x804c4c4c.toInt(), RasterFilters.adjust(red, "grayscale").pixels[0])
        assertEquals(0x80000000.toInt(), RasterFilters.adjust(red, "brightness", -100.0).pixels[0])
        assertEquals(0x80ffffff.toInt(), RasterFilters.adjust(red, "brightness", 100.0).pixels[0])
        assertEquals(0x80000000.toInt(), RasterFilters.adjust(red, "binarize", 128.0).pixels[0])
    }

    @Test
    fun blurPremultipliesAlphaAndChecksWorkBudget() {
        val source = RgbaImage(3, 1, intArrayOf(0, 0x80ff0000.toInt(), 0))
        val out = RasterFilters.blur(source, 1)
        assertTrue(out.pixels.all { Color.red(it) == 255 && Color.green(it) == 0 })
        assertTrue(Color.alpha(out.pixels[0]) > 0)
        assertFailsWith<IllegalArgumentException> { RasterFilters.blur(source, 33) }
        assertFailsWith<IllegalStateException> {
            RasterFilters.blur(source, 1) { error("cancelled") }
        }
    }

    @Test
    fun connectedBackgroundRemovalPreservesEnclosedDetail() {
        val source = RgbaImage(5, 5, IntArray(25) { -1 })
        for (x in 1..3) {
            source.setPixel(x, 1, Color.BLACK)
            source.setPixel(x, 3, Color.BLACK)
        }
        source.setPixel(1, 2, Color.BLACK)
        source.setPixel(3, 2, Color.BLACK)
        val out = RasterFilters.transparent(source, Color.WHITE, 0)
        assertEquals(0, out.getPixel(0, 0))
        assertEquals(Color.WHITE, out.getPixel(2, 2))
        assertEquals(Color.BLACK, out.getPixel(1, 1))
    }

    @Test
    fun cancellationRollsBackWholePixelBatch() {
        val doc = LayeredPixelDocument(20, 20)
        var count = 0
        assertFailsWith<IllegalStateException> {
            doc.apply(
                listOf(
                    LayeredPixelDocument.Action("add_layer"),
                    LayeredPixelDocument.Action(
                        "flood_fill",
                        listOf(LayeredPixelDocument.Point(0, 0)),
                    ),
                )
            ) {
                if (++count > 2) error("cancelled")
            }
        }
        assertEquals(1, doc.layers.size)
        assertFalse(doc.canUndo)
        assertFailsWith<IllegalArgumentException> {
            doc.apply(
                listOf(
                    LayeredPixelDocument.Action(
                        "line",
                        List(4096) { LayeredPixelDocument.Point(it % 2 * 19, 0) },
                        size = 64,
                    )
                )
            )
        }
    }

    @Test
    fun svgRemovesExternalContentAndRejectsEntityDeclarations() {
        val input =
            """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="30" onload="evil()"><script>evil()</script><style>@import 'https://bad';</style><image href="https://bad"/><rect width="10" height="10" fill="url(https://bad)"/><path d="M0 0L1 1"/></svg>"""
        val cleaned = SvgSanitizer.sanitize(input)
        assertFalse("evil" in cleaned)
        assertFalse("https://" in cleaned)
        assertTrue("viewBox=" in cleaned)
        assertTrue("<path" in cleaned)
        assertFailsWith<IllegalArgumentException> {
            SvgSanitizer.sanitize(
                """<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]><svg viewBox="0 0 1 1">&x;</svg>"""
            )
        }
    }

    @Test
    fun headersValidateBeforeDecode() {
        val png = PngEncoder.encode(RgbaImage(3, 2))
        assertEquals(3 to 2, ImageHeader.dimensions(png))
        val jpeg = byteArrayOf(-1, -40, -1, -64, 0, 11, 8, 0, 10, 0, 20, 1, 1, 17, 0)
        assertEquals(20 to 10, ImageHeader.dimensions(jpeg))
        assertFailsWith<IllegalArgumentException> { ImageHeader.dimensions(jpeg.copyOf(9)) }
        assertFailsWith<IllegalStateException> {
            ImageHeader.dimensions("not an image".encodeToByteArray())
        }
    }

    @Test
    fun layerVisibilityLockAndOrderAreEditable() {
        val doc = LayeredPixelDocument(1, 1)
        doc.apply(
            listOf(
                LayeredPixelDocument.Action("set_color", color = Color.WHITE),
                LayeredPixelDocument.Action("pencil", listOf(LayeredPixelDocument.Point(0, 0))),
                LayeredPixelDocument.Action("add_layer"),
                LayeredPixelDocument.Action("set_color", color = Color.BLACK),
                LayeredPixelDocument.Action("pencil", listOf(LayeredPixelDocument.Point(0, 0))),
            )
        )
        assertEquals(Color.BLACK, doc.composite().pixels[0])
        doc.apply(
            listOf(LayeredPixelDocument.Action("set_layer_visible", index = 1, enabled = false))
        )
        assertEquals(Color.WHITE, doc.composite().pixels[0])
        assertFailsWith<IllegalArgumentException> {
            doc.apply(
                listOf(
                    LayeredPixelDocument.Action("pencil", listOf(LayeredPixelDocument.Point(0, 0)))
                )
            )
        }
        doc.apply(
            listOf(
                LayeredPixelDocument.Action("set_layer_visible", index = 1),
                LayeredPixelDocument.Action("move_layer", index = 1, target = 0),
            )
        )
        assertEquals(Color.WHITE, doc.composite().pixels[0])
        assertEquals(0, doc.activeLayer)
    }
}
