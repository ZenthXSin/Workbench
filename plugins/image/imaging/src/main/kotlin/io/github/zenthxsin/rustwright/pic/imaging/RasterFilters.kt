package io.github.zenthxsin.rustwright.pic.imaging

import kotlin.math.*

/**
 * Local CPU filters, with row checkpoints so callers can cancel without publishing partial output.
 */
object RasterFilters {
    fun adjust(
        source: RgbaImage,
        operation: String,
        amount: Double = 0.0,
        checkpoint: () -> Unit = {},
    ): RgbaImage {
        require(amount.isFinite())
        require(
            operation in
                setOf("brightness", "contrast", "saturation", "hue", "grayscale", "binarize")
        )
        require(
            when (operation) {
                "hue" -> amount in -180.0..180.0
                "binarize" -> amount in 0.0..255.0
                else -> amount in -100.0..100.0
            }
        )
        val out = source.copy()
        for (y in 0 until source.height) {
            checkpoint()
            for (x in 0 until source.width) {
                val c = source.getPixel(x, y)
                val r = Color.red(c).toDouble()
                val g = Color.green(c).toDouble()
                val b = Color.blue(c).toDouble()
                val gray = .299 * r + .587 * g + .114 * b
                val rgb =
                    when (operation) {
                        "brightness" -> doubleArrayOf(r, g, b).map { it + amount * 2.55 }
                        "contrast" -> {
                            val f = if (amount == 100.0) 1000.0 else (100 + amount) / (100 - amount)
                            doubleArrayOf(r, g, b).map { (it - 127.5) * f + 127.5 }
                        }
                        "saturation" ->
                            doubleArrayOf(r, g, b).map { gray + (it - gray) * (1 + amount / 100) }
                        "grayscale" -> listOf(gray, gray, gray)
                        "binarize" -> List(3) { if (gray >= amount) 255.0 else 0.0 }
                        else -> {
                            val mx = max(r, max(g, b))
                            val mn = min(r, min(g, b))
                            val delta = mx - mn
                            var hue =
                                if (delta == 0.0) 0.0
                                else
                                    when (mx) {
                                        r -> 60 * ((g - b) / delta % 6)
                                        g -> 60 * ((b - r) / delta + 2)
                                        else -> 60 * ((r - g) / delta + 4)
                                    }
                            hue = ((hue + amount) % 360 + 360) % 360
                            val chroma = delta
                            val part = chroma * (1 - abs((hue / 60) % 2 - 1))
                            val m = mx - chroma
                            val channels =
                                when (hue) {
                                    in 0.0..<60.0 -> doubleArrayOf(chroma, part, 0.0)
                                    in 60.0..<120.0 -> doubleArrayOf(part, chroma, 0.0)
                                    in 120.0..<180.0 -> doubleArrayOf(0.0, chroma, part)
                                    in 180.0..<240.0 -> doubleArrayOf(0.0, part, chroma)
                                    in 240.0..<300.0 -> doubleArrayOf(part, 0.0, chroma)
                                    else -> doubleArrayOf(chroma, 0.0, part)
                                }
                            channels.map { it + m }
                        }
                    }
                out.setPixel(
                    x,
                    y,
                    Color.argb(
                        Color.alpha(c),
                        rgb[0].roundToInt().coerceIn(0, 255),
                        rgb[1].roundToInt().coerceIn(0, 255),
                        rgb[2].roundToInt().coerceIn(0, 255),
                    ),
                )
            }
        }
        return out
    }

    /** Nearest-neighbour rotation into expanded transparent bounds, keeping crisp pixel edges. */
    fun rotate(source: RgbaImage, degrees: Double, checkpoint: () -> Unit = {}): RgbaImage {
        require(degrees.isFinite() && abs(degrees) <= 36000)
        if (degrees % 90 == 0.0) return RasterOperations.rotate(source, (degrees / 90).toInt())
        val a = Math.toRadians(degrees % 360)
        val c = cos(a)
        val s = sin(a)
        val w = ceil(source.width * abs(c) + source.height * abs(s)).toInt()
        val h = ceil(source.width * abs(s) + source.height * abs(c)).toInt()
        RasterOperations.dimensions(w, h)
        val out = RgbaImage(w, h)
        for (y in 0 until h) {
            checkpoint()
            for (x in 0 until w) {
                val dx = x - (w - 1) / 2.0
                val dy = y - (h - 1) / 2.0
                val sx = (c * dx + s * dy + (source.width - 1) / 2.0).roundToInt()
                val sy = (-s * dx + c * dy + (source.height - 1) / 2.0).roundToInt()
                if (sx in 0 until source.width && sy in 0 until source.height)
                    out.setPixel(x, y, source.getPixel(sx, sy))
            }
        }
        return out
    }

    /** Two-pass Gaussian blur on premultiplied color, avoiding dark halos around transparency. */
    fun blur(source: RgbaImage, radius: Int, checkpoint: () -> Unit = {}): RgbaImage {
        require(radius in 0..32)
        if (radius == 0) return source.copy()
        require(source.pixels.size.toLong() * (radius * 2 + 1) * 2 <= 150_000_000) {
            "Blur exceeds work budget; reduce radius/image size"
        }
        val sigma = max(.5, radius / 2.0)
        val kernel =
            DoubleArray(radius * 2 + 1) {
                exp(-((it - radius) * (it - radius)) / (2 * sigma * sigma))
            }
        val sum = kernel.sum()
        kernel.indices.forEach { kernel[it] /= sum }
        fun pass(src: RgbaImage, horizontal: Boolean): RgbaImage {
            val out = RgbaImage(src.width, src.height)
            for (y in 0 until src.height) {
                checkpoint()
                for (x in 0 until src.width) {
                    var alpha = 0.0
                    var red = 0.0
                    var green = 0.0
                    var blue = 0.0
                    for (k in -radius..radius) {
                        val px = if (horizontal) (x + k).coerceIn(0, src.width - 1) else x
                        val py = if (horizontal) y else (y + k).coerceIn(0, src.height - 1)
                        val p = src.getPixel(px, py)
                        val a = Color.alpha(p) * kernel[k + radius]
                        alpha += a
                        red += Color.red(p) * a
                        green += Color.green(p) * a
                        blue += Color.blue(p) * a
                    }
                    if (alpha > .001)
                        out.setPixel(
                            x,
                            y,
                            Color.argb(
                                alpha.roundToInt().coerceIn(0, 255),
                                (red / alpha).roundToInt().coerceIn(0, 255),
                                (green / alpha).roundToInt().coerceIn(0, 255),
                                (blue / alpha).roundToInt().coerceIn(0, 255),
                            ),
                        )
                }
            }
            return out
        }
        return pass(pass(source, true), false)
    }

    fun sharpen(source: RgbaImage, amount: Double, checkpoint: () -> Unit = {}): RgbaImage {
        require(amount.isFinite() && amount in 0.0..100.0)
        val soft = blur(source, 1, checkpoint)
        val out = source.copy()
        for (y in 0 until source.height) {
            checkpoint()
            for (x in 0 until source.width) {
                val c = source.getPixel(x, y)
                val b = soft.getPixel(x, y)
                fun channel(shift: Int): Int {
                    val v = (c ushr shift) and 255
                    return (v + (v - ((b ushr shift) and 255)) * amount / 25)
                        .roundToInt()
                        .coerceIn(0, 255)
                }
                out.setPixel(x, y, Color.argb(Color.alpha(c), channel(16), channel(8), channel(0)))
            }
        }
        return out
    }

    /**
     * Remove matching border-connected background; disconnected same-color details are preserved.
     */
    fun transparent(
        source: RgbaImage,
        color: Int,
        tolerance: Int = 24,
        checkpoint: () -> Unit = {},
    ): RgbaImage {
        require(tolerance in 0..255)
        val out = source.copy()
        val visited = BooleanArray(source.pixels.size)
        val queue = ArrayDeque<Int>()
        fun offer(i: Int) {
            if (!visited[i]) {
                visited[i] = true
                queue.addLast(i)
            }
        }
        for (x in 0 until source.width) {
            offer(x)
            offer((source.height - 1) * source.width + x)
        }
        for (y in 0 until source.height) {
            offer(y * source.width)
            offer(y * source.width + source.width - 1)
        }
        var steps = 0
        while (queue.isNotEmpty()) {
            if (steps++ and 1023 == 0) checkpoint()
            val i = queue.removeFirst()
            val p = source.pixels[i]
            if (
                listOf(0, 8, 16).any {
                    abs(((p ushr it) and 255) - ((color ushr it) and 255)) > tolerance
                }
            )
                continue
            out.pixels[i] = 0
            val x = i % source.width
            val y = i / source.width
            if (x > 0) offer(i - 1)
            if (x < source.width - 1) offer(i + 1)
            if (y > 0) offer(i - source.width)
            if (y < source.height - 1) offer(i + source.width)
        }
        return out
    }
}
