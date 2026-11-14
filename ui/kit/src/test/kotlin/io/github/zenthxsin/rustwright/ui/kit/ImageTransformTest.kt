package io.github.zenthxsin.rustwright.ui.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageTransformTest {
    @Test fun zoomKeepsTheImagePointUnderTheGestureCenter() {
        val t = ImageTransform()
        t.resize(1000f, 1000f, 200f, 200f)
        val point = (150f - 100f - t.offsetX) / t.scale
        t.zoomAt(2f, 150f, 100f)
        assertEquals(point, (150f - 100f - t.offsetX) / t.scale, 0.001f)
        assertEquals(2f, t.zoom)
    }

    @Test fun panCannotLoseTheImageAndResetFitsItAgain() {
        val t = ImageTransform()
        t.resize(1000f, 1000f, 200f, 200f)
        t.zoomAt(2f, 100f, 100f)
        t.pan(10000f, -10000f)
        assertEquals(100f, t.offsetX)
        assertEquals(-100f, t.offsetY)
        t.reset()
        assertEquals(1f, t.zoom)
        assertEquals(0f, t.offsetX)
        assertEquals(200f, t.width)
    }

    @Test fun portraitResizeAndInvalidInputStayFiniteAndWithinBounds() {
        val t = ImageTransform()
        t.resize(100f, 1000f, 200f, 200f)
        t.zoomAt(2f, 100f, 100f)
        t.pan(100f, 100f)
        assertEquals(0f, t.offsetX)
        t.zoomAt(Float.NaN, 0f, 0f)
        assertEquals(2f, t.zoom)
        t.zoomAt(1000f, 100f, 100f)
        assertEquals(32f, t.zoom)
        t.resize(100f, 1000f, 0f, 0f)
        assertTrue(t.scale.isFinite())
        t.zoomAt(0f, 100f, 100f)
        assertEquals(1f, t.zoom)
        assertEquals(0f, t.offsetX)
        assertEquals(0f, t.offsetY)
    }
}
