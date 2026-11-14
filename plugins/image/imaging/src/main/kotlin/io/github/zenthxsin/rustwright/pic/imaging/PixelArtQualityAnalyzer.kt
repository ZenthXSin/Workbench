package io.github.zenthxsin.rustwright.pic.imaging

/** D8: 像素化结果的客观质量报告。 所有指标都由纯像素统计得出，供结果卡片展示与后续"生成-评估-再生成"闭环使用。 */
data class PixelArtQualityReport(
    /** 边框 8px 环带中接近纯白像素的占比（0..1），白底残留程度越低越好。 */
    val backgroundPurity: Float,
    /** 不透明像素占全图比例（0..1）。 */
    val subjectCoverage: Float,
    /** 不透明区域的不同颜色数量。 */
    val colorCount: Int,
    /** 占比最大的颜色所占份额（0..1），过高说明主体扁平/背景未去净。 */
    val dominantColorRatio: Float,
)

object PixelArtQualityAnalyzer {
    private const val BORDER_RING = 8
    private const val WHITE_CHANNEL = 232

    fun analyze(source: Bitmap): PixelArtQualityReport {
        val bitmap = source
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return PixelArtQualityReport(0f, 0f, 0, 0f)
        val pixels = bitmap.pixels

        var totalPixels = 0
        var opaquePixels = 0
        var ringTotal = 0
        var ringWhite = 0
        val opaqueColors = IntArray(w * h) { -1 }

        fun countPixel(color: Int) {
            val opaque = (color ushr 24) >= 128
            totalPixels++
            if (opaque) {
                opaquePixels++
                opaqueColors[opaquePixels - 1] = color and 0xFFFFFF
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val color = pixels[y * w + x]
                countPixel(color)
                val onBorder =
                    x < BORDER_RING ||
                        y < BORDER_RING ||
                        x >= w - BORDER_RING ||
                        y >= h - BORDER_RING
                if (onBorder) {
                    ringTotal++
                    if (isNearWhite(color)) ringWhite++
                }
            }
        }

        java.util.Arrays.sort(opaqueColors, 0, opaquePixels)
        var colorCount = 0
        var dominantCount = 0
        var index = 0
        while (index < opaquePixels) {
            var end = index + 1
            while (end < opaquePixels && opaqueColors[end] == opaqueColors[index]) end++
            colorCount++
            dominantCount = maxOf(dominantCount, end - index)
            index = end
        }
        val dominantShare = if (opaquePixels == 0) 0f else dominantCount.toFloat() / opaquePixels
        return PixelArtQualityReport(
            backgroundPurity =
                if (ringTotal == 0) 0f else ringWhite.toFloat() / ringTotal.toFloat(),
            subjectCoverage =
                if (totalPixels == 0) 0f else opaquePixels.toFloat() / totalPixels.toFloat(),
            colorCount = colorCount,
            dominantColorRatio = dominantShare,
        )
    }

    private fun isNearWhite(color: Int): Boolean =
        ((color shr 16) and 0xFF) >= WHITE_CHANNEL &&
            ((color shr 8) and 0xFF) >= WHITE_CHANNEL &&
            (color and 0xFF) >= WHITE_CHANNEL
}
