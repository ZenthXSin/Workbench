package io.github.zenthxsin.rustwright.pic.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Deterministic image statistics, independent of sessions and UI state. */
object ImageInspection {
    fun analyze(bitmap: Bitmap): String {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = bitmap.pixels
        val total = max(1, width * height)
        val colorStride = max(1, total / 300_000)
        val buckets = HashMap<Int, Int>()
        var colorSamples = 0
        var lumaSum = 0L
        var y = 0
        while (y < height) {
            var row = y * width
            var x = 0
            while (x < width) {
                val pixel = pixels[row + x]
                if (((pixel ushr 24) and 0xFF) >= 128) {
                    val red = (pixel shr 16) and 0xFF
                    val green = (pixel shr 8) and 0xFF
                    val blue = pixel and 0xFF
                    lumaSum += (299L * red + 587L * green + 114L * blue) / 1000L
                    val bucket = (red shr 4) shl 8 or ((green shr 4) shl 4) or (blue shr 4)
                    buckets[bucket] = (buckets[bucket] ?: 0) + 1
                    colorSamples++
                }
                x += colorStride
            }
            y += colorStride
        }
        val edgeStride = max(1, colorStride * 2)
        var edgeCount = 0
        var edgeSamples = 0
        var ey = edgeStride
        while (ey < height - edgeStride) {
            var row = ey * width
            var ex = edgeStride
            while (ex < width - edgeStride) {
                val center = pixelLuma(pixels[row + ex])
                if (center >= 0) {
                    val left = pixelLuma(pixels[row + ex - edgeStride])
                    val right = pixelLuma(pixels[row + ex + edgeStride])
                    val top = pixelLuma(pixels[(ey - edgeStride) * width + ex])
                    val bottom = pixelLuma(pixels[(ey + edgeStride) * width + ex])
                    if (left >= 0 && right >= 0 && top >= 0 && bottom >= 0) {
                        val gx = abs(right - left)
                        val gy = abs(bottom - top)
                        if (sqrt((gx * gx + gy * gy).toDouble()) > 96.0) edgeCount++
                        edgeSamples++
                    }
                }
                ex += edgeStride
            }
            ey += edgeStride
        }
        val edgeDensity = if (edgeSamples == 0) 0.0 else edgeCount.toDouble() / edgeSamples
        val brightness = if (colorSamples == 0) 0 else (lumaSum / colorSamples).toInt()
        val topColors =
            buckets.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { entry ->
                    val key = entry.key
                    val red = ((key shr 8) and 0xF) shl 4 or 0x08
                    val green = ((key shr 4) and 0xF) shl 4 or 0x08
                    val blue = (key and 0xF) shl 4 or 0x08
                    "${colorHex(red, green, blue)} (${entry.value * 100 / max(1, colorSamples)}%)"
                }
        val brightnessText =
            when {
                brightness >= 180 -> "偏亮"
                brightness >= 80 -> "中等"
                else -> "偏暗"
            }
        val edgeText =
            when {
                edgeDensity >= 0.25 -> "边缘丰富，细节较多"
                edgeDensity >= 0.12 -> "边缘适中"
                else -> "边缘较少，画面较平滑"
            }
        return buildString {
            append("尺寸：").append(width).append("x").append(height).append(" 像素\n")
            append("亮度：").append(brightnessText).append("（均值 ").append(brightness).append("）\n")
            append("边缘密度：")
                .append(edgeText)
                .append("（约 ")
                .append((edgeDensity * 100).toInt())
                .append("%）\n")
            append("主色 Top5：").append(topColors.joinToString("、").ifEmpty { "无" })
        }
    }

    fun pixelLuma(pixel: Int): Int {
        if (((pixel ushr 24) and 0xFF) < 128) return -1
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return (299 * red + 587 * green + 114 * blue) / 1000
    }

    private fun colorHex(red: Int, green: Int, blue: Int): String =
        String.format("#%02X%02X%02X", red, green, blue)
}
