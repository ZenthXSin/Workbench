package io.github.zenthxsin.rustwright.pic.imaging

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImagingTest {

    /** 白底上一个红色方块 + 蓝色圆，模拟生图模型给出的贴图。 */
    private fun sprite(size: Int = 256): RgbaImage {
        val img = RgbaImage(size, size)
        for (y in 0 until size) for (x in 0 until size) {
            val inSquare = x in 64 until 192 && y in 64 until 192
            val dx = x - 128; val dy = y - 128
            val inCircle = dx * dx + dy * dy < 40 * 40
            img.setPixel(x, y, when {
                inCircle -> Color.rgb(30, 60, 200)
                inSquare -> Color.rgb(200, 40, 30)
                else -> Color.WHITE
            })
        }
        return img
    }

    @Test
    fun pngRoundTripsThroughImageIo() {
        val img = sprite(32).also { it.setPixel(0, 0, Color.argb(128, 1, 2, 3)) }
        val decoded = ImageIO.read(ByteArrayInputStream(PngEncoder.encode(img)))
        assertEquals(32, decoded.width)
        assertEquals(img.getPixel(5, 5), decoded.getRGB(5, 5))
        assertEquals(Color.argb(128, 1, 2, 3), decoded.getRGB(0, 0))
        assertEquals(img.getPixel(16, 16), decoded.getRGB(16, 16))
    }

    @Test
    fun spritePipelineRemovesWhiteBackgroundAndDownscales() {
        val out = PixelArtProcessor.process(sprite(), PixelProcessingParams.forGeneratedSprite(32, 32))
        assertEquals(32, out.width)
        assertEquals(32, out.height)
        // 角落是白底，应被去掉；中心是蓝色圆
        assertEquals(0, Color.alpha(out.getPixel(0, 0)))
        val center = out.getPixel(16, 16)
        assertTrue(Color.alpha(center) == 255 && Color.blue(center) > Color.red(center), "center=${Integer.toHexString(center)}")
    }

    @Test
    fun nearestScalingKeepsHardEdges() {
        val small = RgbaImage(2, 1, intArrayOf(Color.BLACK, Color.WHITE))
        val big = RgbaImage.scaledNearest(small, 8, 2)
        assertEquals(Color.BLACK, big.getPixel(3, 1))
        assertEquals(Color.WHITE, big.getPixel(4, 0))
    }
}
