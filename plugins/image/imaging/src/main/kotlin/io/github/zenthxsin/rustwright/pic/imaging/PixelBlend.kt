package io.github.zenthxsin.rustwright.pic.imaging

import kotlin.math.roundToInt

enum class PixelBlend {
    NORMAL,
    MULTIPLY,
    SCREEN,
    OVERLAY,
    DARKEN,
    LIGHTEN,
    ADDITION,
    SUBTRACT,
    DIFFERENCE,
    EXCLUSION,
    HARD_LIGHT;

    /** W3C blend+source-over with unassociated RGBA and per-layer opacity 0..255. */
    fun composite(bottom: Int, top: Int, opacity: Int = 255): Int {
        require(opacity in 0..255)
        val ab = Color.alpha(bottom) / 255.0
        val at = Color.alpha(top) / 255.0 * opacity / 255.0
        val ao = at + ab * (1 - at)
        if (ao == 0.0) return 0
        fun channel(shift: Int): Int {
            val b = ((bottom ushr shift) and 255) / 255.0
            val t = ((top ushr shift) and 255) / 255.0
            val blend =
                when (this) {
                    NORMAL -> t
                    MULTIPLY -> b * t
                    SCREEN -> b + t - b * t
                    OVERLAY -> if (b <= .5) 2 * b * t else 1 - 2 * (1 - b) * (1 - t)
                    DARKEN -> minOf(b, t)
                    LIGHTEN -> maxOf(b, t)
                    ADDITION -> minOf(1.0, b + t)
                    SUBTRACT -> maxOf(0.0, b - t)
                    DIFFERENCE -> kotlin.math.abs(b - t)
                    EXCLUSION -> b + t - 2 * b * t
                    HARD_LIGHT -> if (t <= .5) 2 * b * t else 1 - 2 * (1 - b) * (1 - t)
                }
            return ((at * (1 - ab) * t + at * ab * blend + (1 - at) * ab * b) / ao * 255)
                .roundToInt()
                .coerceIn(0, 255)
        }
        return Color.argb(
            (ao * 255).roundToInt().coerceIn(0, 255),
            channel(16),
            channel(8),
            channel(0),
        )
    }
}
