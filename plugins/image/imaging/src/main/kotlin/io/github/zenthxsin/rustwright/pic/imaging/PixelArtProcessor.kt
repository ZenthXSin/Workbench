package io.github.zenthxsin.rustwright.pic.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class CropMode {
    CENTER,
}

enum class AbstractionStyle {
    DOTS,
    LINES,
    PLANES,
    GEOMETRIC,
}

enum class DeepLearningBackend {
    NONE,
    LOCAL_PLACEHOLDER,
    VICKY_PLACEHOLDER,
}

enum class PaletteMode {
    TOLERANCE,
    MEDIAN_CUT,
}

enum class BackgroundRemovalMode {
    WHITE,
    CUSTOM,
    NONE,
}

enum class PixelSamplingMode {
    /** v1: cell mean + soft edge fade; block size does not drive sample window. */
    MODERN,
    /** v2: block-sized center sample window, center/nearest opaque sample, hard coverage cut. */
    MODERN_V2,
}

data class CropBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class PixelBlockCandidate(
    val size: Int,
    val frequency: Int,
)

data class PixelProcessingParams(
    val outputWidth: Int = 64,
    val outputHeight: Int = 64,
    val blockWidth: Int = 16,
    val blockHeight: Int = 16,
    val samplingMode: PixelSamplingMode = PixelSamplingMode.MODERN,
    /** Let V2 infer its logical pixel grid from image frequency and edge data. */
    val autoDetectGrid: Boolean = false,
    val cropMode: CropMode = CropMode.CENTER,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val backgroundTolerance: Int = 60,
    val edgeTolerance: Int = 150,
    val backgroundRemovalMode: BackgroundRemovalMode = BackgroundRemovalMode.WHITE,
    val backgroundColor: Int = Color.WHITE,
    val alphaThreshold: Int = 128,
    val colorMergeTolerance: Int = 0,
    val maxPaletteColors: Int = 32,
    val paletteMode: PaletteMode = PaletteMode.TOLERANCE,
    val cavityThreshold: Int = 0,
    val edgeCoverageThreshold: Int = 35,
    val noiseReduction: Int = 45,
    val abstractionEnabled: Boolean = false,
    val abstractionStyle: AbstractionStyle = AbstractionStyle.PLANES,
    val shapeScale: Int = 80,
    val lineLength: Int = 75,
    val lineThickness: Int = 35,
    val regionMergeTolerance: Int = 28,
    val featureSensitivity: Int = 45,
    val shapeVariety: Int = 50,
    val preserveEdges: Int = 60,
    val deepLearningEnabled: Boolean = false,
    val deepLearningBackend: DeepLearningBackend = DeepLearningBackend.NONE,
    val aiMaskStrength: Int = 50,
    val aiEdgeGuidance: Int = 50,
    val aiPaletteGuidance: Int = 40,
    val aiPresetStrength: Int = 50,
    /** D4: 去背 loose 容差扩张的最大环数，限制边缘浅色漏进主体形成孔洞的深度。 */
    val looseExpansionRings: Int = 3,
    /** D7: 边缘覆盖率硬切后对轮廓的外扩补偿环数（0 关闭）。 */
    val edgeDilation: Int = 1,
    /** D5: 锁定调色板（不透明像素会被量化为其中最接近的颜色），null 表示不锁定。 */
    val paletteColors: List<Int>? = null,
) {
    val cropWidth: Int get() = outputWidth * blockWidth
    val cropHeight: Int get() = outputHeight * blockHeight

    fun normalized(
        maxOutputWidth: Int = MAX_OUTPUT_SIZE,
        maxOutputHeight: Int = MAX_OUTPUT_SIZE,
    ): PixelProcessingParams {
        val widthCap = maxOutputSize(maxOutputWidth)
        val heightCap = maxOutputSize(maxOutputHeight)
        return copy(
            outputWidth = outputWidth.coerceIn(MIN_OUTPUT_SIZE, widthCap),
            outputHeight = outputHeight.coerceIn(MIN_OUTPUT_SIZE, heightCap),
            blockWidth = blockWidth.coerceIn(MIN_BLOCK_SIZE, MAX_BLOCK_SIZE),
            blockHeight = blockHeight.coerceIn(MIN_BLOCK_SIZE, MAX_BLOCK_SIZE),
            cropLeft = cropLeft.coerceIn(0f, 0.99f),
            cropTop = cropTop.coerceIn(0f, 0.99f),
            cropRight = cropRight.coerceIn(0.01f, 1f).coerceAtLeast(cropLeft.coerceIn(0f, 0.99f) + 0.01f),
            cropBottom = cropBottom.coerceIn(0.01f, 1f).coerceAtLeast(cropTop.coerceIn(0f, 0.99f) + 0.01f),
            backgroundTolerance = backgroundTolerance.coerceIn(0, 255),
            edgeTolerance = edgeTolerance.coerceIn(0, 255),
            alphaThreshold = alphaThreshold.coerceIn(1, 254),
            colorMergeTolerance = colorMergeTolerance.coerceIn(0, 255),
            maxPaletteColors = maxPaletteColors.coerceIn(MIN_PALETTE_COLORS, MAX_PALETTE_COLORS),
            cavityThreshold = cavityThreshold.coerceIn(0, 255),
            edgeCoverageThreshold = edgeCoverageThreshold.coerceIn(0, 100),
            noiseReduction = noiseReduction.coerceIn(0, 100),
            shapeScale = shapeScale.coerceIn(10, 140),
            lineLength = lineLength.coerceIn(10, 160),
            lineThickness = lineThickness.coerceIn(5, 120),
            regionMergeTolerance = regionMergeTolerance.coerceIn(0, 255),
            featureSensitivity = featureSensitivity.coerceIn(0, 100),
            shapeVariety = shapeVariety.coerceIn(0, 100),
            preserveEdges = preserveEdges.coerceIn(0, 100),
            aiMaskStrength = aiMaskStrength.coerceIn(0, 100),
            aiEdgeGuidance = aiEdgeGuidance.coerceIn(0, 100),
            aiPaletteGuidance = aiPaletteGuidance.coerceIn(0, 100),
            aiPresetStrength = aiPresetStrength.coerceIn(0, 100),
            looseExpansionRings = looseExpansionRings.coerceIn(0, 8),
            edgeDilation = edgeDilation.coerceIn(0, 4),
        )
    }

    fun withAutoBlocks(sourceWidth: Int, sourceHeight: Int): PixelProcessingParams {
        val maxWidth = maxOutputSize(sourceWidth)
        val maxHeight = maxOutputSize(sourceHeight)
        val normalized = normalized(maxOutputWidth = maxWidth, maxOutputHeight = maxHeight)
        return normalized.copy(
            blockWidth = autoBlockSize(croppedSourceWidth(sourceWidth, sourceHeight), normalized.outputWidth),
            blockHeight = autoBlockSize(croppedSourceHeight(sourceWidth, sourceHeight), normalized.outputHeight),
        )
    }

    fun withAutoHeight(sourceWidth: Int, sourceHeight: Int): PixelProcessingParams {
        val maxWidth = maxOutputSize(sourceWidth)
        val maxHeight = maxOutputSize(sourceHeight)
        val normalized = normalized(maxOutputWidth = maxWidth, maxOutputHeight = maxHeight)
        val croppedWidth = croppedSourceWidth(sourceWidth, sourceHeight)
        val croppedHeight = croppedSourceHeight(sourceWidth, sourceHeight)
        val computedHeight = if (croppedWidth <= 0 || croppedHeight <= 0) {
            normalized.outputHeight
        } else {
            (normalized.outputWidth.toFloat() * croppedHeight / croppedWidth).roundToInt()
                .coerceIn(MIN_OUTPUT_SIZE, maxHeight)
        }
        return normalized.copy(outputHeight = computedHeight)
    }

    fun croppedSourceWidth(sourceWidth: Int, sourceHeight: Int): Int = cropBounds(sourceWidth, sourceHeight).width

    fun croppedSourceHeight(sourceWidth: Int, sourceHeight: Int): Int = cropBounds(sourceWidth, sourceHeight).height

    fun cropBounds(sourceWidth: Int, sourceHeight: Int): CropBounds = cropBounds(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropRight = cropRight,
        cropBottom = cropBottom,
    )

    companion object {
        const val MIN_OUTPUT_SIZE = 8
        /** Absolute safety ceiling; UI / auto helpers further cap by source image size. */
        const val MAX_OUTPUT_SIZE = 4096
        const val MIN_BLOCK_SIZE = 1
        const val MAX_BLOCK_SIZE = 128
        const val MIN_PALETTE_COLORS = 2
        const val MAX_PALETTE_COLORS = 256

        /** Generated art already has an authored grid; automatic export must preserve its accents and silhouette. */
        fun forGeneratedSprite(outputWidth: Int, outputHeight: Int): PixelProcessingParams = PixelProcessingParams(
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            samplingMode = PixelSamplingMode.MODERN_V2,
            autoDetectGrid = false,
            backgroundRemovalMode = BackgroundRemovalMode.WHITE,
            backgroundTolerance = 45,
            edgeTolerance = 120,
            edgeCoverageThreshold = 30,
            looseExpansionRings = 3,
            colorMergeTolerance = 0,
            paletteMode = PaletteMode.TOLERANCE,
            noiseReduction = 0,
            edgeDilation = 0,
        )

        fun maxOutputSize(sourceSize: Int): Int =
            sourceSize.coerceIn(MIN_OUTPUT_SIZE, MAX_OUTPUT_SIZE)

        fun outputSizeRange(sourceSize: Int?): IntRange {
            val max = if (sourceSize == null || sourceSize <= 0) {
                MAX_OUTPUT_SIZE
            } else {
                maxOutputSize(sourceSize)
            }
            return MIN_OUTPUT_SIZE..max
        }

        fun autoFor(sourceWidth: Int, sourceHeight: Int, outputWidth: Int = 64, outputHeight: Int = outputWidth): PixelProcessingParams {
            return PixelProcessingParams(
                outputWidth = outputWidth,
                outputHeight = outputHeight,
            ).withAutoBlocks(sourceWidth, sourceHeight)
        }

        fun cropBounds(
            sourceWidth: Int,
            sourceHeight: Int,
            cropLeft: Float,
            cropTop: Float,
            cropRight: Float,
            cropBottom: Float,
        ): CropBounds {
            require(sourceWidth > 0) { "sourceWidth must be positive" }
            require(sourceHeight > 0) { "sourceHeight must be positive" }
            val normalizedLeft = cropLeft.coerceIn(0f, 0.99f)
            val normalizedTop = cropTop.coerceIn(0f, 0.99f)
            val normalizedRight = cropRight.coerceIn(0.01f, 1f).coerceAtLeast(normalizedLeft + 0.01f)
            val normalizedBottom = cropBottom.coerceIn(0.01f, 1f).coerceAtLeast(normalizedTop + 0.01f)
            val left = floor(normalizedLeft * sourceWidth).toInt().coerceIn(0, sourceWidth - 1)
            val top = floor(normalizedTop * sourceHeight).toInt().coerceIn(0, sourceHeight - 1)
            val right = ceil(normalizedRight * sourceWidth).toInt().coerceIn(left + 1, sourceWidth)
            val bottom = ceil(normalizedBottom * sourceHeight).toInt().coerceIn(top + 1, sourceHeight)
            return CropBounds(left = left, top = top, right = right, bottom = bottom)
        }

        /**
         * Center-fit sample window inside [crop]: ideal size is output×block, clamped to crop.
         * Used by [PixelSamplingMode.MODERN_V2] so logical blocks actually drive sampling area.
         */
        fun sampleBounds(
            crop: CropBounds,
            outputWidth: Int,
            outputHeight: Int,
            blockWidth: Int,
            blockHeight: Int,
        ): CropBounds {
            val idealW = (outputWidth.coerceAtLeast(1) * blockWidth.coerceAtLeast(1)).coerceAtLeast(1)
            val idealH = (outputHeight.coerceAtLeast(1) * blockHeight.coerceAtLeast(1)).coerceAtLeast(1)
            val sampleW = min(idealW, crop.width.coerceAtLeast(1)).coerceAtLeast(1)
            val sampleH = min(idealH, crop.height.coerceAtLeast(1)).coerceAtLeast(1)
            val left = crop.left + (crop.width - sampleW) / 2
            val top = crop.top + (crop.height - sampleH) / 2
            return CropBounds(
                left = left,
                top = top,
                right = left + sampleW,
                bottom = top + sampleH,
            )
        }

        private fun autoBlockSize(sourceSize: Int, outputSize: Int): Int {
            if (sourceSize <= 0 || outputSize <= 0) return MIN_BLOCK_SIZE
            return max(MIN_BLOCK_SIZE, sourceSize / outputSize).coerceAtMost(MAX_BLOCK_SIZE)
        }
    }
}

data class DeepLearningHints(
    val subjectMask: IntArray? = null,
    val edgeMap: IntArray? = null,
    val paletteSuggestions: List<Int> = emptyList(),
    val recommendedParams: PixelProcessingParams? = null,
)

interface DeepLearningImageEnhancer {
    fun enhance(source: Bitmap, transparent: Bitmap, params: PixelProcessingParams): DeepLearningHints
}

object NoOpDeepLearningImageEnhancer : DeepLearningImageEnhancer {
    override fun enhance(source: Bitmap, transparent: Bitmap, params: PixelProcessingParams): DeepLearningHints = DeepLearningHints()
}

object PixelArtProcessor {
    fun ensureArgb8888(source: Bitmap): Bitmap {
        return if (source.config == RgbaImage.Config.ARGB_8888) source else source.copy(RgbaImage.Config.ARGB_8888, false)
    }

    fun removeBackground(
        source: Bitmap,
        backgroundTolerance: Int = 60,
        edgeTolerance: Int = 150,
        backgroundColor: Int = Color.WHITE,
        looseExpansionRings: Int = 3,
    ): Bitmap {
        val bitmap = ensureArgb8888(source)
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val targetRed = (backgroundColor shr 16) and 0xFF
        val targetGreen = (backgroundColor shr 8) and 0xFF
        val targetBlue = backgroundColor and 0xFF

        val visited = BooleanArray(w * h)
        val queue = IntArray(max(1, w * h * 2))
        val strict = backgroundTolerance.coerceIn(0, 255).toDouble()
        val loose = edgeTolerance.coerceIn(backgroundTolerance, 255).toDouble()
        var head = 0
        var tail = 0

        fun dist(idx: Int): Double {
            val p = pixels[idx]
            val dr = targetRed - ((p shr 16) and 0xFF)
            val dg = targetGreen - ((p shr 8) and 0xFF)
            val db = targetBlue - (p and 0xFF)
            return sqrt((dr * dr + dg * dg + db * db).toDouble())
        }

        fun enqueue(idx: Int) {
            queue[tail % queue.size] = idx
            tail++
        }

        for (x in 0 until w) {
            val top = x
            val bottom = (h - 1) * w + x
            if (dist(top) <= strict && !visited[top]) {
                visited[top] = true
                enqueue(top)
            }
            if (dist(bottom) <= strict && !visited[bottom]) {
                visited[bottom] = true
                enqueue(bottom)
            }
        }
        for (y in 0 until h) {
            val left = y * w
            val right = y * w + w - 1
            if (dist(left) <= strict && !visited[left]) {
                visited[left] = true
                enqueue(left)
            }
            if (dist(right) <= strict && !visited[right]) {
                visited[right] = true
                enqueue(right)
            }
        }

        while (head < tail) {
            val idx = queue[head % queue.size]
            head++
            pixels[idx] = Color.TRANSPARENT
            val x = idx % w
            val y = idx / w
            forEachNeighbor4(x, y, w, h) { nx, ny ->
                val next = ny * w + nx
                if (!visited[next] && dist(next) <= strict) {
                    visited[next] = true
                    enqueue(next)
                }
            }
        }

        // D4: loose 容差扩张做深度限制（从严格背景边缘最多向外扩 maxRings 环），
        // 防止主体贴边时浅色区域被一路"漏"进主体内部形成大孔洞。
        val maxRings = looseExpansionRings.coerceIn(0, 8)
        if (maxRings > 0) {
            val depth = IntArray(w * h)
            head = 0
            tail = 0
            for (idx in pixels.indices) {
                if (!visited[idx]) continue
                val x = idx % w
                val y = idx / w
                forEachNeighbor4(x, y, w, h) { nx, ny ->
                    val next = ny * w + nx
                    if (!visited[next]) {
                        depth[next] = 1
                        enqueue(next)
                    }
                }
            }

            while (head < tail) {
                val idx = queue[head % queue.size]
                head++
                if (visited[idx]) continue
                if (dist(idx) <= loose) {
                    visited[idx] = true
                    pixels[idx] = Color.TRANSPARENT
                    val currentDepth = depth[idx]
                    if (currentDepth >= maxRings) continue
                    val x = idx % w
                    val y = idx / w
                    forEachNeighbor4(x, y, w, h) { nx, ny ->
                        val next = ny * w + nx
                        if (!visited[next]) {
                            depth[next] = currentDepth + 1
                            enqueue(next)
                        }
                    }
                }
            }
        }

        return Bitmap.createBitmap(w, h, RgbaImage.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    fun detectBlockSizeCandidates(bitmap: Bitmap, tolerance: Int = 8): List<PixelBlockCandidate> {
        val source = ensureArgb8888(bitmap)
        val w = source.width
        val h = source.height
        if (w <= 1 || h <= 1) return emptyList()

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val transitions = mutableListOf<Int>()

        for (i in 1..19) {
            val y = h * i / 20
            var lastX = 0
            var lastRgb = pixels[y * w]
            for (x in 1 until w) {
                val rgb = pixels[y * w + x]
                if (!isColorSimilar(rgb, lastRgb, tolerance)) {
                    val distance = x - lastX
                    if (distance in 1..300) transitions += distance
                    lastX = x
                    lastRgb = rgb
                }
            }
        }

        for (i in 1..19) {
            val x = w * i / 20
            var lastY = 0
            var lastRgb = pixels[x]
            for (y in 1 until h) {
                val rgb = pixels[y * w + x]
                if (!isColorSimilar(rgb, lastRgb, tolerance)) {
                    val distance = y - lastY
                    if (distance in 1..300) transitions += distance
                    lastY = y
                    lastRgb = rgb
                }
            }
        }

        if (transitions.isEmpty()) return emptyList()
        val groups = mutableMapOf<Int, Int>()
        transitions.sorted().forEach { length ->
            val existing = groups.keys.firstOrNull { abs(it - length) <= 5 }
            if (existing != null) groups[existing] = groups.getValue(existing) + 1 else groups[length] = 1
        }
        return groups.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { PixelBlockCandidate(size = it.key, frequency = it.value) }
    }

    fun removeCavities(source: Bitmap, threshold: Int): Bitmap {
        if (threshold <= 0) return source
        val bitmap = ensureArgb8888(source)
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        fun dist(idx: Int): Double {
            val p = pixels[idx]
            val dr = 255 - ((p shr 16) and 0xFF)
            val dg = 255 - ((p shr 8) and 0xFF)
            val db = 255 - (p and 0xFF)
            return sqrt((dr * dr + dg * dg + db * db).toDouble())
        }

        val queue = IntArray(max(1, w * h * 2))
        val visited = BooleanArray(w * h)
        val thresholdDistance = threshold.coerceIn(0, 255).toDouble()

        for (startIdx in pixels.indices) {
            if (visited[startIdx]) continue
            if ((pixels[startIdx] ushr 24) == 0) continue
            if (dist(startIdx) > thresholdDistance) continue

            val component = mutableListOf<Int>()
            var touchesBorder = false
            var head = 0
            var tail = 0
            visited[startIdx] = true
            queue[tail++] = startIdx

            while (head < tail) {
                val idx = queue[head++]
                component += idx
                val x = idx % w
                val y = idx / w
                if (x == 0 || x == w - 1 || y == 0 || y == h - 1) touchesBorder = true
                forEachNeighbor4(x, y, w, h) { nx, ny ->
                    val next = ny * w + nx
                    if (visited[next] || (pixels[next] ushr 24) == 0 || dist(next) > thresholdDistance) return@forEachNeighbor4
                    visited[next] = true
                    queue[tail++] = next
                }
            }

            if (!touchesBorder) {
                for (idx in component) pixels[idx] = Color.TRANSPARENT
            }
        }

        return Bitmap.createBitmap(w, h, RgbaImage.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    fun convertToPixelArt(source: Bitmap, params: PixelProcessingParams): Bitmap {
        val bitmap = ensureArgb8888(source)
        val srcW = bitmap.width
        val srcH = bitmap.height
        val normalized = params.normalized(
            maxOutputWidth = PixelProcessingParams.maxOutputSize(srcW),
            maxOutputHeight = PixelProcessingParams.maxOutputSize(srcH),
        )
        val srcPixels = IntArray(srcW * srcH)
        bitmap.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        val targetW = normalized.outputWidth
        val targetH = normalized.outputHeight
        val dstPixels = IntArray(targetW * targetH)
        val cropBounds = normalized.cropBounds(srcW, srcH)
        val cropW = cropBounds.width
        val cropH = cropBounds.height
        val cropStartX = cropBounds.left
        val cropStartY = cropBounds.top

        for (ty in 0 until targetH) {
            for (tx in 0 until targetW) {
                val x0 = cropStartX + floor(tx.toDouble() * cropW / targetW).toInt()
                val x1 = cropStartX + ceil((tx + 1).toDouble() * cropW / targetW).toInt()
                val y0 = cropStartY + floor(ty.toDouble() * cropH / targetH).toInt()
                val y1 = cropStartY + ceil((ty + 1).toDouble() * cropH / targetH).toInt()
                val samples = ColorBucket()
                var alphaSum = 0L

                for (sy in y0 until y1.coerceAtMost(srcH)) {
                    for (sx in x0 until x1.coerceAtMost(srcW)) {
                        val pixel = srcPixels[sy * srcW + sx]
                        val alpha = (pixel ushr 24) and 0xFF
                        if (alpha >= min(normalized.alphaThreshold, 32)) {
                            alphaSum += alpha
                            samples.add(pixel, alpha)
                        }
                    }
                }

                val blockArea = max(1, (x1 - x0) * (y1 - y0))
                val coveragePercent = (alphaSum * 100 / (blockArea * 255L)).toInt()
                val averageAlpha = (alphaSum / blockArea).toInt().coerceIn(0, 255)
                val outputAlpha = if (normalized.edgeCoverageThreshold > 0 && coveragePercent < normalized.edgeCoverageThreshold) {
                    averageAlpha * coveragePercent / normalized.edgeCoverageThreshold
                } else {
                    averageAlpha
                }
                dstPixels[ty * targetW + tx] = if (samples.weight == 0L || outputAlpha == 0) {
                    Color.TRANSPARENT
                } else {
                    samples.color(alpha = outputAlpha)
                }
            }
        }

        return Bitmap.createBitmap(targetW, targetH, RgbaImage.Config.ARGB_8888).also {
            it.setPixels(dstPixels, 0, targetW, 0, 0, targetW, targetH)
        }
    }

    fun convertToPixelArtV2(source: Bitmap, params: PixelProcessingParams): Bitmap {
        val bitmap = ensureArgb8888(source)
        val srcW = bitmap.width
        val srcH = bitmap.height
        val normalized = params.normalized(
            maxOutputWidth = PixelProcessingParams.maxOutputSize(srcW),
            maxOutputHeight = PixelProcessingParams.maxOutputSize(srcH),
        )
        val srcPixels = IntArray(srcW * srcH)
        bitmap.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        val targetW = normalized.outputWidth
        val targetH = normalized.outputHeight
        val dstPixels = IntArray(targetW * targetH)
        val cropBounds = normalized.cropBounds(srcW, srcH)
        val sample = PixelProcessingParams.sampleBounds(
            crop = cropBounds,
            outputWidth = targetW,
            outputHeight = targetH,
            blockWidth = normalized.blockWidth,
            blockHeight = normalized.blockHeight,
        )
        val sampleW = sample.width
        val sampleH = sample.height
        val sampleStartX = sample.left
        val sampleStartY = sample.top
        val minAlpha = min(normalized.alphaThreshold, 32)

        for (ty in 0 until targetH) {
            for (tx in 0 until targetW) {
                val x0 = sampleStartX + floor(tx.toDouble() * sampleW / targetW).toInt()
                val x1 = sampleStartX + ceil((tx + 1).toDouble() * sampleW / targetW).toInt()
                val y0 = sampleStartY + floor(ty.toDouble() * sampleH / targetH).toInt()
                val y1 = sampleStartY + ceil((ty + 1).toDouble() * sampleH / targetH).toInt()
                val cellX0 = x0.coerceIn(0, srcW)
                val cellX1 = x1.coerceIn(cellX0, srcW)
                val cellY0 = y0.coerceIn(0, srcH)
                val cellY1 = y1.coerceIn(cellY0, srcH)
                val cellW = cellX1 - cellX0
                val cellH = cellY1 - cellY0
                if (cellW <= 0 || cellH <= 0) {
                    dstPixels[ty * targetW + tx] = Color.TRANSPARENT
                    continue
                }

                var alphaSum = 0L
                var opaqueCount = 0
                for (sy in cellY0 until cellY1) {
                    val row = sy * srcW
                    for (sx in cellX0 until cellX1) {
                        val alpha = (srcPixels[row + sx] ushr 24) and 0xFF
                        if (alpha >= minAlpha) {
                            alphaSum += alpha
                            opaqueCount++
                        }
                    }
                }

                val blockArea = max(1, cellW * cellH)
                val coveragePercent = (alphaSum * 100 / (blockArea * 255L)).toInt()
                if (normalized.edgeCoverageThreshold > 0 && coveragePercent < normalized.edgeCoverageThreshold) {
                    dstPixels[ty * targetW + tx] = Color.TRANSPARENT
                    continue
                }
                if (opaqueCount == 0) {
                    dstPixels[ty * targetW + tx] = Color.TRANSPARENT
                    continue
                }

                val cx = (cellX0 + cellX1 - 1) / 2
                val cy = (cellY0 + cellY1 - 1) / 2
                val centerPixel = srcPixels[cy * srcW + cx]
                val centerAlpha = (centerPixel ushr 24) and 0xFF
                val sampled = if (centerAlpha >= minAlpha) {
                    centerPixel
                } else {
                    nearestOpaqueInCell(
                        srcPixels = srcPixels,
                        srcW = srcW,
                        cellX0 = cellX0,
                        cellY0 = cellY0,
                        cellX1 = cellX1,
                        cellY1 = cellY1,
                        cx = cx,
                        cy = cy,
                        minAlpha = minAlpha,
                    )
                }
                dstPixels[ty * targetW + tx] = sampled ?: Color.TRANSPARENT
            }
        }

        return Bitmap.createBitmap(targetW, targetH, RgbaImage.Config.ARGB_8888).also {
            it.setPixels(dstPixels, 0, targetW, 0, 0, targetW, targetH)
        }
    }

    private fun nearestOpaqueInCell(
        srcPixels: IntArray,
        srcW: Int,
        cellX0: Int,
        cellY0: Int,
        cellX1: Int,
        cellY1: Int,
        cx: Int,
        cy: Int,
        minAlpha: Int,
    ): Int? {
        var bestPixel: Int? = null
        var bestDist = Long.MAX_VALUE
        for (sy in cellY0 until cellY1) {
            val row = sy * srcW
            val dy = (sy - cy).toLong()
            for (sx in cellX0 until cellX1) {
                val pixel = srcPixels[row + sx]
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha < minAlpha) continue
                val dx = (sx - cx).toLong()
                val dist = dx * dx + dy * dy
                if (dist < bestDist) {
                    bestDist = dist
                    bestPixel = pixel
                    if (dist == 0L) return pixel
                }
            }
        }
        return bestPixel
    }

    /**
     * [Rustwright] RustPic 的矢量抽象化依赖 android.graphics.Canvas，暂未移植：原样返回。
     */
    fun renderAbstraction(bitmap: Bitmap, params: PixelProcessingParams): Bitmap = bitmap

    fun mergeColors(bitmap: Bitmap, tolerance: Int): Bitmap {
        if (tolerance <= 0) return bitmap
        val source = ensureArgb8888(bitmap)
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val frequency = mutableMapOf<Int, Int>()
        for (pixel in pixels) {
            if (((pixel shr 24) and 0xFF) >= 128) frequency[pixel] = (frequency[pixel] ?: 0) + 1
        }
        if (frequency.isEmpty()) return source

        val clusters = mutableListOf<PaletteCluster>()
        frequency.entries.sortedByDescending { it.value }.forEach { (color, count) ->
            val match = clusters.minByOrNull { colorDistance(color, it.representative) }
                ?.takeIf { colorDistance(color, it.representative) <= tolerance }
            if (match == null) clusters += PaletteCluster(color, count) else match.add(color, count)
        }
        val remap = frequency.keys.associateWith { color ->
            clusters.minBy { colorDistance(color, it.representative) }.representative
        }

        for (i in pixels.indices) {
            val pixel = pixels[i]
            if (((pixel shr 24) and 0xFF) >= 128) pixels[i] = remap[pixel] ?: pixel
        }

        return Bitmap.createBitmap(w, h, RgbaImage.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    fun quantizePalette(bitmap: Bitmap, maxColors: Int): Bitmap {
        val source = ensureArgb8888(bitmap)
        val limit = maxColors.coerceIn(PixelProcessingParams.MIN_PALETTE_COLORS, PixelProcessingParams.MAX_PALETTE_COLORS)
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val frequency = pixels.asSequence()
            .filter { (it ushr 24) >= 128 }
            .groupingBy { it }
            .eachCount()
        if (frequency.size <= limit) return source

        val boxes = mutableListOf(ColorBox(frequency.entries.map { WeightedColor(it.key, it.value) }))
        while (boxes.size < limit) {
            val index = boxes.indices
                .filter { boxes[it].colors.size > 1 }
                .maxByOrNull { boxes[it].score } ?: break
            val box = boxes.removeAt(index)
            val (first, second) = box.split()
            boxes += first
            boxes += second
        }
        val palette = boxes.map { it.representative() }
        val remap = frequency.keys.associateWith { color -> palette.minBy { colorDistance(color, it) } }
        for (i in pixels.indices) {
            if ((pixels[i] ushr 24) >= 128) pixels[i] = remap.getValue(pixels[i])
        }
        return Bitmap.createBitmap(w, h, RgbaImage.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    fun cleanupIsolatedPixels(bitmap: Bitmap, tolerance: Int, strength: Int): Bitmap {
        if (strength <= 0) return bitmap
        val source = ensureArgb8888(bitmap)
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        val output = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        pixels.copyInto(output)
        val normalizedStrength = strength.coerceIn(0, 100)
        val mergeThreshold = max(12.0, tolerance.toDouble())
        val replaceThreshold = max(18.0, 82.0 - normalizedStrength * 0.5)
        val requiredNeighbors = (7 - normalizedStrength / 34).coerceIn(5, 7)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val pixel = pixels[idx]
                if (((pixel ushr 24) and 0xFF) < 128) continue
                val neighbors = mutableMapOf<Int, Int>()
                var similarCount = 0
                var opaqueNeighbors = 0
                var touchesTransparency = false
                for (ny in max(0, y - 1)..minOf(h - 1, y + 1)) {
                    for (nx in max(0, x - 1)..minOf(w - 1, x + 1)) {
                        if (nx == x && ny == y) continue
                        val neighbor = pixels[ny * w + nx]
                        if (((neighbor ushr 24) and 0xFF) < 128) {
                            touchesTransparency = true
                            continue
                        }
                        opaqueNeighbors++
                        if (colorDistance(pixel, neighbor) <= mergeThreshold) similarCount++
                        neighbors[neighbor] = (neighbors[neighbor] ?: 0) + 1
                    }
                }
                if (touchesTransparency || opaqueNeighbors < 8 || similarCount > 1) continue
                val dominant = neighbors.maxByOrNull { it.value } ?: continue
                if (dominant.value < requiredNeighbors || colorDistance(pixel, dominant.key) <= replaceThreshold) continue

                val pixelLuma = colorLuma(pixel)
                val dominantLuma = colorLuma(dominant.key)
                val fillsDarkInterior = dominantLuma + 24 < pixelLuma && dominant.value == 8
                if (dominantLuma + 12 < pixelLuma && !fillsDarkInterior) continue
                output[idx] = dominant.key
            }
        }

        return Bitmap.createBitmap(w, h, RgbaImage.Config.ARGB_8888).also {
            it.setPixels(output, 0, w, 0, 0, w, h)
        }
    }

    /**
     * D7: 轮廓外扩补偿。透明像素周围有至少 2 个不透明邻居时，用邻居众数色填充，
     * 修复覆盖率硬切造成的轮廓收缩/断裂。strength 为重复轮数。
     */
    fun dilateEdges(bitmap: Bitmap, strength: Int = 1): Bitmap {
        if (strength <= 0) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return bitmap
        var current = ensureArgb8888(bitmap)
        val rounds = strength.coerceIn(0, 4)
        for (round in 0 until rounds) {
            val pixels = IntArray(w * h)
            current.getPixels(pixels, 0, w, 0, 0, w, h)
            val output = pixels.copyOf()
            var changed = false
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    if (((pixels[idx] ushr 24) and 0xFF) >= 128) continue
                    val neighbors = mutableListOf<Int>()
                    forEachNeighbor4(x, y, w, h) { nx, ny ->
                        val p = pixels[ny * w + nx]
                        if (((p ushr 24) and 0xFF) >= 128) neighbors.add(p)
                    }
                    if (neighbors.size >= 2) {
                        val dominant = neighbors.groupingBy { it }.eachCount()
                            .maxByOrNull { it.value }?.key ?: continue
                        output[idx] = dominant
                        changed = true
                    }
                }
            }
            current = Bitmap.createBitmap(w, h, RgbaImage.Config.ARGB_8888).also {
                it.setPixels(output, 0, w, 0, 0, w, h)
            }
            if (!changed) break
        }
        return current
    }

    /**
     * D5: 提取不透明区域出现频率最高的前 maxColors 种颜色，作为可锁定的参考调色板。
     */
    fun extractPalette(bitmap: Bitmap, maxColors: Int): List<Int> {
        val source = ensureArgb8888(bitmap)
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val counts = HashMap<Int, Int>()
        for (pixel in pixels) {
            if (((pixel ushr 24) and 0xFF) < 128) continue
            val key = pixel and 0xFFFFFF
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(maxColors.coerceIn(2, PixelProcessingParams.MAX_PALETTE_COLORS))
            .map { it.key }
    }

    /**
     * D5: 将不透明像素量化为调色板中颜色距离最近的颜色（带缓存），透明像素保持不变。
     */
    fun quantizeToPalette(bitmap: Bitmap, palette: List<Int>): Bitmap {
        val colors = palette.distinct().take(PixelProcessingParams.MAX_PALETTE_COLORS)
        if (colors.isEmpty()) return bitmap
        val source = ensureArgb8888(bitmap)
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val cache = HashMap<Int, Int>()
        for (index in pixels.indices) {
            val pixel = pixels[index]
            if (((pixel ushr 24) and 0xFF) < 128) continue
            pixels[index] = cache.getOrPut(pixel) {
                colors.minByOrNull { candidate -> colorDistance(pixel, candidate) } ?: pixel
            }
        }
        return Bitmap.createBitmap(w, h, RgbaImage.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    fun process(
        source: Bitmap,
        params: PixelProcessingParams,
        deepLearningImageEnhancer: DeepLearningImageEnhancer = NoOpDeepLearningImageEnhancer,
    ): Bitmap {
        val normalized = params.normalized(
            maxOutputWidth = PixelProcessingParams.maxOutputSize(source.width),
            maxOutputHeight = PixelProcessingParams.maxOutputSize(source.height),
        )
        val transparent = when (normalized.backgroundRemovalMode) {
            BackgroundRemovalMode.WHITE -> removeBackground(
                source = source,
                backgroundTolerance = normalized.backgroundTolerance,
                edgeTolerance = normalized.edgeTolerance,
                backgroundColor = Color.WHITE,
                looseExpansionRings = normalized.looseExpansionRings,
            )
            BackgroundRemovalMode.CUSTOM -> removeBackground(
                source = source,
                backgroundTolerance = normalized.backgroundTolerance,
                edgeTolerance = normalized.edgeTolerance,
                backgroundColor = normalized.backgroundColor,
                looseExpansionRings = normalized.looseExpansionRings,
            )
            BackgroundRemovalMode.NONE -> ensureArgb8888(source)
        }
        val hints = if (normalized.deepLearningEnabled) {
            deepLearningImageEnhancer.enhance(source, transparent, normalized)
        } else {
            DeepLearningHints()
        }
        val effectiveParams = mergeRecommendedParams(normalized, hints)
        val withoutCavities = removeCavities(transparent, effectiveParams.cavityThreshold)
        val pixelArt = when (effectiveParams.samplingMode) {
            PixelSamplingMode.MODERN -> convertToPixelArt(withoutCavities, effectiveParams)
            PixelSamplingMode.MODERN_V2 -> PerfectPixelProcessor.process(
                source = withoutCavities,
                sampleMethod = PerfectPixelProcessor.SampleMethod.CENTER,
                gridSize = PerfectPixelProcessor.resolveGridSize(effectiveParams),
                minSize = 4f,
                peakWidth = 6,
                refineIntensity = .25f,
                fixSquare = true,
                removeBackground = false,
            )
        }
        // D7: 边缘覆盖率硬切会让轮廓收缩/断裂，按需做 1..n 环外扩补偿。
        val dilated = if (effectiveParams.edgeCoverageThreshold > 0 && effectiveParams.edgeDilation > 0) {
            dilateEdges(pixelArt, effectiveParams.edgeDilation)
        } else {
            pixelArt
        }
        val abstracted = if (effectiveParams.abstractionEnabled) renderAbstraction(dilated, effectiveParams) else dilated
        val toleranceMerged = if (effectiveParams.colorMergeTolerance > 0) {
            mergeColors(abstracted, effectiveParams.colorMergeTolerance)
        } else {
            abstracted
        }
        val unified = when (effectiveParams.paletteMode) {
            PaletteMode.TOLERANCE -> toleranceMerged
            PaletteMode.MEDIAN_CUT -> quantizePalette(toleranceMerged, effectiveParams.maxPaletteColors)
        }
        // D5: 锁定调色板——最终不透明像素强制映射到用户指定的调色板。
        val paletteLocked = effectiveParams.paletteColors
            ?.takeIf { it.isNotEmpty() }
            ?.let { quantizeToPalette(unified, it) }
            ?: unified
        return cleanupIsolatedPixels(paletteLocked, effectiveParams.colorMergeTolerance, effectiveParams.noiseReduction)
    }

    private fun mergeRecommendedParams(params: PixelProcessingParams, hints: DeepLearningHints): PixelProcessingParams {
        val recommended = hints.recommendedParams ?: return params
        val strength = params.aiPresetStrength.coerceIn(0, 100)
        if (strength == 0) return params
        fun blend(current: Int, next: Int): Int = (current * (100 - strength) + next * strength) / 100
        return params.copy(
            edgeCoverageThreshold = blend(params.edgeCoverageThreshold, recommended.edgeCoverageThreshold),
            noiseReduction = blend(params.noiseReduction, recommended.noiseReduction),
            colorMergeTolerance = blend(params.colorMergeTolerance, recommended.colorMergeTolerance),
            regionMergeTolerance = blend(params.regionMergeTolerance, recommended.regionMergeTolerance),
            featureSensitivity = blend(params.featureSensitivity, recommended.featureSensitivity),
            preserveEdges = blend(params.preserveEdges, recommended.preserveEdges),
        ).normalized()
    }

    private fun featureScore(pixels: IntArray, x: Int, y: Int, w: Int, h: Int): Double {
        val center = pixels[y * w + x]
        if (((center shr 24) and 0xFF) < 128) return 0.0
        var total = 0.0
        var count = 0
        for (ny in max(0, y - 1)..min(h - 1, y + 1)) {
            for (nx in max(0, x - 1)..min(w - 1, x + 1)) {
                if (nx == x && ny == y) continue
                val neighbor = pixels[ny * w + nx]
                total += if (((neighbor shr 24) and 0xFF) < 128) 255.0 else colorDistance(center, neighbor).coerceAtMost(255.0)
                count++
            }
        }
        return if (count == 0) 0.0 else total / count
    }

    private fun dominantDirection(pixels: IntArray, x: Int, y: Int, w: Int, h: Int): Float {
        val left = lumaAt(pixels, max(0, x - 1), y, w)
        val right = lumaAt(pixels, min(w - 1, x + 1), y, w)
        val top = lumaAt(pixels, x, max(0, y - 1), w)
        val bottom = lumaAt(pixels, x, min(h - 1, y + 1), w)
        val gx = right - left
        val gy = bottom - top
        return (atan2(gy.toDouble(), gx.toDouble()) + PI / 2.0).toFloat()
    }

    private fun groupRegions(pixels: IntArray, w: Int, h: Int, tolerance: Int): List<PixelRegion> {
        val visited = BooleanArray(w * h)
        val regions = mutableListOf<PixelRegion>()
        val queue = IntArray(max(1, w * h))
        val threshold = tolerance.coerceIn(0, 255)

        for (start in pixels.indices) {
            if (visited[start]) continue
            val startColor = pixels[start]
            if (((startColor shr 24) and 0xFF) < 128) {
                visited[start] = true
                continue
            }

            var head = 0
            var tail = 0
            var minX = start % w
            var maxX = minX
            var minY = start / w
            var maxY = minY
            val bucket = ColorBucket()
            visited[start] = true
            queue[tail++] = start

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % w
                val y = idx / w
                val color = pixels[idx]
                bucket.add(color)
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)

                forEachNeighbor4(x, y, w, h) { nx, ny ->
                    val next = ny * w + nx
                    if (visited[next]) return@forEachNeighbor4
                    val nextColor = pixels[next]
                    if (((nextColor shr 24) and 0xFF) < 128 || colorDistance(startColor, nextColor) > threshold) return@forEachNeighbor4
                    visited[next] = true
                    queue[tail++] = next
                }
            }

            regions += PixelRegion(
                minX = minX,
                minY = minY,
                maxX = maxX,
                maxY = maxY,
                color = bucket.color(),
                count = bucket.count,
            )
        }

        return regions
    }

    private inline fun forEachNeighbor4(x: Int, y: Int, w: Int, h: Int, block: (Int, Int) -> Unit) {
        if (x + 1 < w) block(x + 1, y)
        if (x - 1 >= 0) block(x - 1, y)
        if (y + 1 < h) block(x, y + 1)
        if (y - 1 >= 0) block(x, y - 1)
    }

    private fun isColorSimilar(first: Int, second: Int, tolerance: Int): Boolean {
        val firstAlpha = (first shr 24) and 0xFF
        val secondAlpha = (second shr 24) and 0xFF
        if (firstAlpha < 128 && secondAlpha < 128) return true
        if ((firstAlpha < 128) != (secondAlpha < 128)) return false
        return abs(((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)) <= tolerance &&
            abs(((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)) <= tolerance &&
            abs((first and 0xFF) - (second and 0xFF)) <= tolerance
    }

    private fun lumaAt(pixels: IntArray, x: Int, y: Int, w: Int): Int {
        val color = pixels[y * w + x]
        if (((color shr 24) and 0xFF) < 128) return 255
        return ((color shr 16) and 0xFF) * 30 + ((color shr 8) and 0xFF) * 59 + (color and 0xFF) * 11
    }

    private data class PixelRegion(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val color: Int,
        val count: Int,
    )

    private class ColorBucket {
        var count = 0
        var weight = 0L
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L

        fun add(color: Int, alpha: Int = 255) {
            val weight = alpha.coerceIn(0, 255).toLong()
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF
            count++
            this.weight += weight
            redSum += red * weight
            greenSum += green * weight
            blueSum += blue * weight
        }

        fun color(alpha: Int = 255): Int {
            if (weight == 0L) return Color.TRANSPARENT
            return (alpha.coerceIn(0, 255) shl 24) or
                ((redSum / weight).toInt() shl 16) or
                ((greenSum / weight).toInt() shl 8) or
                (blueSum / weight).toInt()
        }
    }

    private class PaletteCluster(color: Int, count: Int) {
        private var redSum = 0
        private var greenSum = 0
        private var blueSum = 0
        private var weight = 0
        var representative = color
            private set

        init {
            add(color, count)
        }

        fun add(color: Int, count: Int) {
            redSum += ((color shr 16) and 0xFF) * count
            greenSum += ((color shr 8) and 0xFF) * count
            blueSum += (color and 0xFF) * count
            weight += count
            representative = (0xFF shl 24) or
                ((redSum / weight) shl 16) or
                ((greenSum / weight) shl 8) or
                (blueSum / weight)
        }
    }

    private data class WeightedColor(val color: Int, val count: Int)

    private class ColorBox(val colors: List<WeightedColor>) {
        private val redRange = channelRange(16)
        private val greenRange = channelRange(8)
        private val blueRange = channelRange(0)
        private val totalWeight = colors.sumOf { it.count }
        val score: Long = max(redRange, max(greenRange, blueRange)).toLong() * totalWeight

        fun split(): Pair<ColorBox, ColorBox> {
            val shift = when (max(redRange, max(greenRange, blueRange))) {
                redRange -> 16
                greenRange -> 8
                else -> 0
            }
            val sorted = colors.sortedWith(compareBy<WeightedColor> { (it.color shr shift) and 0xFF }.thenBy { it.color })
            val midpoint = totalWeight / 2
            var accumulated = 0
            var splitAt = 1
            for (i in 0 until sorted.lastIndex) {
                accumulated += sorted[i].count
                if (accumulated >= midpoint) {
                    splitAt = i + 1
                    break
                }
            }
            return ColorBox(sorted.subList(0, splitAt)) to ColorBox(sorted.subList(splitAt, sorted.size))
        }

        fun representative(): Int {
            var red = 0L
            var green = 0L
            var blue = 0L
            for ((color, count) in colors) {
                red += ((color shr 16) and 0xFF).toLong() * count
                green += ((color shr 8) and 0xFF).toLong() * count
                blue += (color and 0xFF).toLong() * count
            }
            return (0xFF shl 24) or
                ((red / totalWeight).toInt() shl 16) or
                ((green / totalWeight).toInt() shl 8) or
                (blue / totalWeight).toInt()
        }

        private fun channelRange(shift: Int): Int {
            val values = colors.map { (it.color shr shift) and 0xFF }
            return values.max() - values.min()
        }
    }

    private fun colorLuma(color: Int): Int {
        return (((color shr 16) and 0xFF) * 30 +
            ((color shr 8) and 0xFF) * 59 +
            (color and 0xFF) * 11) / 100
    }

    private fun colorDistance(first: Int, second: Int): Double {
        val dr = ((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)
        val dg = ((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)
        val db = (first and 0xFF) - (second and 0xFF)
        val redMean = (((first shr 16) and 0xFF) + ((second shr 16) and 0xFF)) / 2
        val redWeight = 2.0 + redMean / 256.0
        val blueWeight = 2.0 + (255 - redMean) / 256.0
        return sqrt(redWeight * dr * dr + 4.0 * dg * dg + blueWeight * db * db)
    }
}
