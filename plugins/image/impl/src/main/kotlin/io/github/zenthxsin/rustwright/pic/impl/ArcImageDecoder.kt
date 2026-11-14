package io.github.zenthxsin.rustwright.pic.impl

import arc.graphics.Pixmap
import io.github.zenthxsin.rustwright.pic.imaging.RgbaImage

/** 用 Arc 的 Pixmap（stb_image 原生解码）把 PNG/JPEG 解成 ARGB 像素；不需要 GL 上下文。 */
object ArcImageDecoder : ImageDecoder {
    override fun decode(bytes: ByteArray): RgbaImage {
        val pix = Pixmap(bytes)
        try {
            val w = pix.width
            val h = pix.height
            val out = RgbaImage(w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val rgba = pix.get(x, y)
                out.pixels[y * w + x] = (rgba ushr 8) or (rgba shl 24)
            }
            return out
        } finally {
            pix.dispose()
        }
    }
}
