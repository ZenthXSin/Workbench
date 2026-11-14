package io.github.zenthxsin.rustwright.desktop

import kotlin.test.*

class DesktopSvgRasterizerTest {
    @Test fun rendersShapesAtRequestedAspectWithTransparentBackground() {
        val image = DesktopSvgRasterizer().render("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 10"><rect x="0" y="0" width="10" height="10" fill="#ff0000"/></svg>""", 200)
        assertEquals(200, image.width)
        assertEquals(100, image.height)
        assertEquals(0xffff0000.toInt(), image.pixels[50 * image.width + 50])
        assertEquals(0, image.pixels[50 * image.width + 150])
    }
    @Test fun normalizesSvgWithoutExplicitNamespace() {
        val image = DesktopSvgRasterizer().render("""<svg viewBox="0 0 10 20"><rect width="10" height="20" fill="#00ff00"/></svg>""", 40)
        assertEquals(20, image.width)
        assertEquals(40, image.height)
        assertEquals(0xff00ff00.toInt(), image.pixels[20 * image.width + 10])
    }
    @Test fun rejectsOversizedOutputAndEntityDeclarations() {
        val renderer = DesktopSvgRasterizer()
        assertFailsWith<IllegalArgumentException> { renderer.render("<svg/>", 4096) }
        assertFailsWith<IllegalArgumentException> { renderer.render("""<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]><svg viewBox="0 0 10 10">&x;</svg>""", 10) }
    }
}
