package io.github.zenthxsin.rustwright.pic.imaging

import kotlin.math.abs
import kotlin.math.roundToInt

/** Deterministic image operations shared by tools and future tool-mode pages. ARGB internally. */
object RasterOperations {
    const val MAX_PIXELS = 4_194_304

    fun dimensions(w: Int, h: Int) {
        require(w in 1..8192 && h in 1..8192 && w.toLong() * h <= MAX_PIXELS) {
            "Image exceeds 8192 px edge or 4M pixel budget"
        }
    }

    fun color(hex: String): Int {
        require(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(hex)) {
            "Use #RRGGBB or #RRGGBBAA"
        }
        val v = hex.drop(1).toLong(16)
        return if (hex.length == 7) (0xff000000L or v).toInt()
        else ((v ushr 8) or ((v and 255) shl 24)).toInt()
    }

    fun hex(c: Int) = "#%08X".format((c.toLong() and 0xffffff) shl 8 or ((c ushr 24).toLong()))

    fun crop(src: RgbaImage, x: Int, y: Int, w: Int, h: Int): RgbaImage {
        dimensions(w, h)
        require(x >= 0 && y >= 0 && x.toLong() + w <= src.width && y.toLong() + h <= src.height) {
            "Crop outside image"
        }
        return RgbaImage(w, h).also { out ->
            repeat(h) {
                src.pixels.copyInto(
                    out.pixels,
                    it * w,
                    (y + it) * src.width + x,
                    (y + it) * src.width + x + w,
                )
            }
        }
    }

    fun rotate(src: RgbaImage, quarterTurns: Int): RgbaImage {
        val turns = ((quarterTurns % 4) + 4) % 4
        var image = src.copy()
        repeat(turns) {
            val out = RgbaImage(image.height, image.width)
            for (y in 0 until image.height) for (x in 0 until image.width) out.setPixel(
                image.height - 1 - y,
                x,
                image.getPixel(x, y),
            )
            image = out
        }
        return image
    }

    fun flip(src: RgbaImage, horizontal: Boolean): RgbaImage =
        RgbaImage(src.width, src.height).also { out ->
            for (y in 0 until src.height) for (x in 0 until src.width) out.setPixel(
                if (horizontal) src.width - 1 - x else x,
                if (horizontal) y else src.height - 1 - y,
                src.getPixel(x, y),
            )
        }

    fun palette(src: RgbaImage, limit: Int = 32): List<Pair<Int, Int>> {
        require(limit in 1..256)
        // Primitive sort keeps exact counts with O(pixels) bytes, instead of millions of boxed map
        // entries.
        val colors = src.pixels.copyOf().also { it.sort() }
        val best =
            java.util.PriorityQueue<Pair<Int, Int>>(
                compareBy<Pair<Int, Int>> { it.second }.thenByDescending { it.first }
            )
        var i = 0
        while (i < colors.size) {
            val color = colors[i]
            var end = i + 1
            while (end < colors.size && colors[end] == color) end++
            if (Color.alpha(color) > 0) {
                best.add(color to end - i)
                if (best.size > limit) best.remove()
            }
            i = end
        }
        return best.sortedWith(
            compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first }
        )
    }

    fun quantize(src: RgbaImage, palette: List<Int>, checkpoint: () -> Unit = {}): RgbaImage {
        require(palette.size in 1..256)
        require(src.pixels.size.toLong() * palette.size <= 16_777_216) {
            "Quantization exceeds work budget; reduce image or palette size"
        }
        val cache = HashMap<Int, Int>()
        val output = src.copy()
        for (y in 0 until src.height) {
            checkpoint()
            for (x in 0 until src.width) {
                val i = y * src.width + x
                val color = src.pixels[i]
                if (Color.alpha(color) == 0) {
                    output.pixels[i] = 0
                    continue
                }
                output.pixels[i] =
                    cache[color]
                        ?: run {
                            var best = palette[0]
                            var minimum = Int.MAX_VALUE
                            for (candidate in palette) {
                                val r = Color.red(color) - Color.red(candidate)
                                val g = Color.green(color) - Color.green(candidate)
                                val b = Color.blue(color) - Color.blue(candidate)
                                val distance = r * r + g * g + b * b
                                if (distance < minimum) {
                                    minimum = distance
                                    best = candidate
                                }
                            }
                            val value = (color and -0x1000000) or (best and 0xffffff)
                            if (cache.size >= 4096) cache.clear()
                            cache[color] = value
                            value
                        }
            }
        }
        return output
    }

    /** Row-major frames; reject non-divisible sheets instead of silently discarding pixels. */
    fun split(src: RgbaImage, cols: Int, rows: Int): List<RgbaImage> {
        require(cols in 1..256 && rows in 1..256 && cols * rows <= 256)
        require(src.width % cols == 0 && src.height % rows == 0) {
            "Sheet must be divisible by grid dimensions"
        }
        val w = src.width / cols
        val h = src.height / rows
        return List(cols * rows) { crop(src, it % cols * w, it / cols * h, w, h) }
    }

    fun join(frames: List<RgbaImage>, columns: Int, spacing: Int = 0): RgbaImage {
        require(frames.size in 1..256 && columns in 1..frames.size && spacing in 0..256)
        val w = frames.maxOf { it.width }
        val h = frames.maxOf { it.height }
        val rows = (frames.size + columns - 1) / columns
        val ow = columns * w + (columns - 1) * spacing
        val oh = rows * h + (rows - 1) * spacing
        dimensions(ow, oh)
        return RgbaImage(ow, oh).also { out ->
            frames.forEachIndexed { i, f ->
                repeat(f.height) { y ->
                    f.pixels.copyInto(
                        out.pixels,
                        (i / columns * (h + spacing) + y) * ow + i % columns * (w + spacing),
                        y * f.width,
                        (y + 1) * f.width,
                    )
                }
            }
        }
    }

    data class Difference(
        val changedPixels: Int,
        val meanAbsoluteChannelError: Double,
        val maxChannelError: Int,
    )

    fun compare(a: RgbaImage, b: RgbaImage): Difference {
        require(a.width == b.width && a.height == b.height) {
            "Comparison requires equal dimensions"
        }
        var changed = 0
        var sum = 0L
        var max = 0
        for (i in a.pixels.indices) {
            if (a.pixels[i] != b.pixels[i]) changed++
            for (s in listOf(0, 8, 16, 24)) {
                val d = abs(((a.pixels[i] ushr s) and 255) - ((b.pixels[i] ushr s) and 255))
                sum += d
                max = maxOf(max, d)
            }
        }
        return Difference(changed, sum.toDouble() / (a.pixels.size * 4L), max)
    }

    /**
     * Porter-Duff source-over with straight-alpha input/output, including translucent destination.
     */
    fun over(dst: Int, src: Int): Int {
        val sa = Color.alpha(src) / 255.0
        val da = Color.alpha(dst) / 255.0
        val alpha = sa + da * (1 - sa)
        if (alpha == 0.0) return 0
        fun ch(shift: Int) =
            ((((src ushr shift) and 255) * sa + ((dst ushr shift) and 255) * da * (1 - sa)) / alpha)
                .roundToInt()
                .coerceIn(0, 255)
        return Color.argb((alpha * 255).roundToInt(), ch(16), ch(8), ch(0))
    }
}
