package io.github.zenthxsin.rustwright.pic.imaging

/**
 * 纯 Kotlin 的 ARGB_8888 位图（像素为 0xAARRGGBB）。
 * 方法签名对齐 android.graphics.Bitmap 中像素处理用到的那部分，便于移植 RustPic 的处理代码。
 */
class RgbaImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(width >= 0 && height >= 0 && pixels.size == width * height) { "尺寸与像素数不符" }
    }

    /** 恒为 ARGB_8888，仅为兼容原 Bitmap 代码。 */
    val config: Config get() = Config.ARGB_8888

    fun getPixel(x: Int, y: Int): Int = pixels[y * width + x]
    fun setPixel(x: Int, y: Int, color: Int) { pixels[y * width + x] = color }

    fun getPixels(out: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        for (row in 0 until h) System.arraycopy(pixels, (y + row) * width + x, out, offset + row * stride, w)
    }

    fun setPixels(src: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        for (row in 0 until h) System.arraycopy(src, offset + row * stride, pixels, (y + row) * width + x, w)
    }

    fun copy(@Suppress("UNUSED_PARAMETER") config: Config = Config.ARGB_8888, @Suppress("UNUSED_PARAMETER") mutable: Boolean = true): RgbaImage =
        RgbaImage(width, height, pixels.copyOf())

    enum class Config { ARGB_8888 }

    companion object {
        fun createBitmap(width: Int, height: Int, @Suppress("UNUSED_PARAMETER") config: Config = Config.ARGB_8888) = RgbaImage(width, height)

        /** 最近邻缩放（像素风不做平滑）。 */
        fun scaledNearest(src: RgbaImage, w: Int, h: Int): RgbaImage {
            val out = RgbaImage(w, h)
            for (y in 0 until h) {
                val sy = (y * src.height / h).coerceAtMost(src.height - 1)
                for (x in 0 until w) out.pixels[y * w + x] = src.pixels[sy * src.width + (x * src.width / w).coerceAtMost(src.width - 1)]
            }
            return out
        }
    }
}

/** 对齐 android.graphics.Color 中处理代码用到的常量与分量函数。 */
object Color {
    const val TRANSPARENT = 0
    const val WHITE = -0x1
    const val BLACK = -0x1000000

    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    fun rgb(r: Int, g: Int, b: Int) = argb(0xFF, r, g, b)
}

/** 原代码里的 Bitmap 类型名。 */
typealias Bitmap = RgbaImage
