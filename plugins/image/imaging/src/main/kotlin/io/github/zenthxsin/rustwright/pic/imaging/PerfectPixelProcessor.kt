package io.github.zenthxsin.rustwright.pic.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Kotlin port of theamusing/perfectPixel's no-CV2 algorithm. */
object PerfectPixelProcessor {
    enum class SampleMethod { MAJORITY, CENTER, MEDIAN }

    data class Params(
        val outputWidth: Int,
        val outputHeight: Int,
        val alphaThreshold: Int = 1,
        val coverageThreshold: Int = 0,
        val maxPaletteColors: Int = 0,
        val cropLeft: Float = 0f,
        val cropTop: Float = 0f,
        val cropRight: Float = 1f,
        val cropBottom: Float = 1f,
    )

    fun process(source: Bitmap, params: PixelProcessingParams): Bitmap {
        val p = params.normalized(PixelProcessingParams.maxOutputSize(source.width), PixelProcessingParams.maxOutputSize(source.height))
        val method = when (p.samplingMode) {
            PixelSamplingMode.MODERN_V2 -> SampleMethod.CENTER
            PixelSamplingMode.MODERN -> SampleMethod.MAJORITY
        }
        val grid = resolveGridSize(p)
        return process(source, method, grid, 4f, 6, .25f, true, removeBackground = true)
    }

    internal fun resolveGridSize(params: PixelProcessingParams): Pair<Int, Int>? =
        if (params.autoDetectGrid) null else params.outputWidth to params.outputHeight

    /** An explicit grid partitions the entire source without moving boundaries toward image edges. */
    internal fun fixedGridCoordinates(sourceSize: Int, requestedCells: Int): IntArray {
        require(sourceSize > 0)
        val cells = requestedCells.coerceIn(1, sourceSize)
        return IntArray(cells + 1) { index -> (index.toLong() * sourceSize / cells).toInt() }
    }

    fun process(
        source: Bitmap,
        sampleMethod: SampleMethod = SampleMethod.CENTER,
        gridSize: Pair<Int, Int>? = null,
        minSize: Float = 4f,
        peakWidth: Int = 6,
        refineIntensity: Float = .25f,
        fixSquare: Boolean = true,
        removeBackground: Boolean = false,
    ): Bitmap {
        val input = PixelArtProcessor.ensureArgb8888(source)
        require(input.width > 0 && input.height > 0)
        val w = input.width; val h = input.height
        val rgb = IntArray(w * h)
        input.getPixels(rgb, 0, w, 0, 0, w, h)

        // flat row-major float image: index [y*w*3 + x*3 + c]
        val imgF = FloatArray(h * w * 3) { idx ->
            val px = rgb[idx / 3]
            when (idx % 3) { 0 -> Color.red(px).toFloat(); 1 -> Color.green(px).toFloat(); else -> Color.blue(px).toFloat() }
        }
        // Preserve source alpha so upstream flood-fill transparency survives sampling.
        val alphaF = FloatArray(w * h) { i -> Color.alpha(rgb[i]).toFloat() }
        val gray = FloatArray(w * h) { i ->
            .299f * Color.red(rgb[i]).toFloat() + .587f * Color.green(rgb[i]).toFloat() + .114f * Color.blue(rgb[i]).toFloat()
        }

        val (xCoords, yCoords) = if (gridSize != null) {
            // A requested 1024-cell axis on a 1024px source is valid: preserve every source pixel.
            fixedGridCoordinates(w, gridSize.first) to fixedGridCoordinates(h, gridSize.second)
        } else {
            val (gridW, gridH) = detectGridScale(gray, w, h, peakWidth, 1.5f, minSize)
                ?: (max(1, w / 4) to max(1, h / 4))
            refineGrids(gray, w, h, gridW, gridH, refineIntensity)
        }

        var scaledRgb = when (sampleMethod) {
            SampleMethod.CENTER   -> sampleCenter(imgF, w, h, xCoords, yCoords)
            SampleMethod.MEDIAN   -> sampleMedian(imgF, w, h, xCoords, yCoords)
            SampleMethod.MAJORITY -> sampleMajority(imgF, w, h, xCoords, yCoords)
        }
        var scaledAlpha = when (sampleMethod) {
            SampleMethod.CENTER   -> sampleCenterAlpha(alphaF, w, h, xCoords, yCoords)
            SampleMethod.MEDIAN   -> sampleMedianAlpha(alphaF, w, h, xCoords, yCoords)
            SampleMethod.MAJORITY -> sampleMajorityAlpha(alphaF, w, h, xCoords, yCoords)
        }
        var outW = xCoords.size - 1; var outH = yCoords.size - 1

        if (fixSquare && gridSize == null && abs(outW - outH) == 1) {
            val fixed = applyFixSquare(scaledRgb, scaledAlpha, outW, outH)
            scaledRgb = fixed.rgb; scaledAlpha = fixed.alpha; outW = fixed.w; outH = fixed.h
        }

        val pixels = IntArray(outW * outH) { i ->
            val a = scaledAlpha[i].roundToInt().coerceIn(0, 255)
            if (a <= 0) {
                Color.TRANSPARENT
            } else {
                Color.argb(
                    a,
                    scaledRgb[i * 3].roundToInt().coerceIn(0, 255),
                    scaledRgb[i * 3 + 1].roundToInt().coerceIn(0, 255),
                    scaledRgb[i * 3 + 2].roundToInt().coerceIn(0, 255),
                )
            }
        }
        if (removeBackground && outW > 0 && outH > 0) applyBackgroundTransparency(pixels, outW, outH)
        return Bitmap.createBitmap(outW, outH, RgbaImage.Config.ARGB_8888).also { it.setPixels(pixels, 0, outW, 0, 0, outW, outH) }
    }

    fun process(source: Bitmap, sampleMethod: String, gridSize: Pair<Int, Int>? = null, minSize: Float = 4f, peakWidth: Int = 6, refineIntensity: Float = .25f, fixSquare: Boolean = true, removeBackground: Boolean = false): Bitmap =
        process(source, when (sampleMethod.lowercase()) { "majority" -> SampleMethod.MAJORITY; "median" -> SampleMethod.MEDIAN; else -> SampleMethod.CENTER }, gridSize, minSize, peakWidth, refineIntensity, fixSquare, removeBackground)

    fun resizeNearest(source: Bitmap, width: Int, height: Int): Bitmap =
        RgbaImage.scaledNearest(source, width, height)

    // ── background transparency ───────────────────────────────────────────────

    private fun applyBackgroundTransparency(pixels: IntArray, w: Int, h: Int) {
        val corners = intArrayOf(pixels[0], pixels[w - 1], pixels[(h - 1) * w], pixels[(h - 1) * w + w - 1])
        val bgColor = corners.groupBy { it }.maxByOrNull { it.value.size }!!.key
        val bgR = Color.red(bgColor); val bgG = Color.green(bgColor); val bgB = Color.blue(bgColor)
        for (i in pixels.indices) {
            val r = Color.red(pixels[i]); val g = Color.green(pixels[i]); val b = Color.blue(pixels[i])
            val dist = (r - bgR) * (r - bgR) + (g - bgG) * (g - bgG) + (b - bgB) * (b - bgB)
            if (dist < 32 * 32) pixels[i] = Color.argb(0, r, g, b)
        }
    }

    // ── fix-square ────────────────────────────────────────────────────────────

    private data class FixSquareResult(
        val rgb: FloatArray,
        val alpha: FloatArray,
        val w: Int,
        val h: Int,
    )

    private fun applyFixSquare(rgb: FloatArray, alpha: FloatArray, w: Int, h: Int): FixSquareResult {
        if (abs(w - h) != 1) return FixSquareResult(rgb, alpha, w, h)
        return if (w > h) {
            if (w % 2 == 1) {
                val outRgb = FloatArray(h * (w - 1) * 3)
                val outA = FloatArray(h * (w - 1))
                for (y in 0 until h) {
                    System.arraycopy(rgb, y * w * 3, outRgb, y * (w - 1) * 3, (w - 1) * 3)
                    System.arraycopy(alpha, y * w, outA, y * (w - 1), w - 1)
                }
                FixSquareResult(outRgb, outA, w - 1, h)
            } else {
                // 追加一行：在底部复制最后一行，保持内容不发生整体位移。
                val outRgb = FloatArray((h + 1) * w * 3)
                val outA = FloatArray((h + 1) * w)
                System.arraycopy(rgb, 0, outRgb, 0, rgb.size)
                System.arraycopy(rgb, (h - 1) * w * 3, outRgb, h * w * 3, w * 3)
                System.arraycopy(alpha, 0, outA, 0, alpha.size)
                System.arraycopy(alpha, (h - 1) * w, outA, h * w, w)
                FixSquareResult(outRgb, outA, w, h + 1)
            }
        } else {
            if (h % 2 == 1) {
                val outRgb = FloatArray((h - 1) * w * 3)
                val outA = FloatArray((h - 1) * w)
                System.arraycopy(rgb, 0, outRgb, 0, outRgb.size)
                System.arraycopy(alpha, 0, outA, 0, outA.size)
                FixSquareResult(outRgb, outA, w, h - 1)
            } else {
                // 追加一列：在每行末尾复制该行最后一个像素，而不是行首像素。
                val outRgb = FloatArray(h * (w + 1) * 3)
                val outA = FloatArray(h * (w + 1))
                for (y in 0 until h) {
                    System.arraycopy(rgb, y * w * 3, outRgb, y * (w + 1) * 3, w * 3)
                    System.arraycopy(rgb, (y * w + w - 1) * 3, outRgb, y * (w + 1) * 3 + w * 3, 3)
                    System.arraycopy(alpha, y * w, outA, y * (w + 1), w)
                    outA[y * (w + 1) + w] = alpha[y * w + w - 1]
                }
                FixSquareResult(outRgb, outA, w + 1, h)
            }
        }
    }

    // ── grid detection ────────────────────────────────────────────────────────

    private fun detectGridScale(gray: FloatArray, w: Int, h: Int, peakWidth: Int, maxRatio: Float, minSize: Float): Pair<Int, Int>? {
        var (gridW, gridH) = estimateGridFft(gray, w, h, peakWidth) ?: run {
            estimateGridGradient(gray, w, h) ?: return null
        }
        val pxX = w.toFloat() / gridW; val pxY = h.toFloat() / gridH
        if (pxX / pxY > maxRatio || pxY / pxX > maxRatio || pxX < minSize || pxY < minSize || max(pxX, pxY) > 20f) {
            val fallback = estimateGridGradient(gray, w, h) ?: return null
            gridW = fallback.first; gridH = fallback.second
        }
        val pxX2 = w.toFloat() / gridW; val pxY2 = h.toFloat() / gridH
        val pixelSize = if (pxX2 / pxY2 > maxRatio || pxY2 / pxX2 > maxRatio) min(pxX2, pxY2) else (pxX2 + pxY2) / 2f
        return (w / pixelSize).roundToInt().coerceAtLeast(1) to (h / pixelSize).roundToInt().coerceAtLeast(1)
    }

    private fun estimateGridFft(gray: FloatArray, w: Int, h: Int, peakWidth: Int): Pair<Int, Int>? {
        val mag = computeFftMagnitude(gray, w, h)
        val rowSum = FloatArray(h) { y -> var s = 0f; for (x in 0 until w) s += mag[y * w + x]; s }
        val colSum = FloatArray(w) { x -> var s = 0f; for (y in 0 until h) s += mag[y * w + x]; s }
        val rowN = normalizeMinMax(rowSum); val colN = normalizeMinMax(colSum)
        val rowS = smooth1d(rowN, 17); val colS = smooth1d(colN, 17)
        val scaleRow = detectPeak(rowS, peakWidth) ?: return null
        val scaleCol = detectPeak(colS, peakWidth) ?: return null
        if (scaleCol <= 0) return null
        return scaleCol.roundToInt().coerceAtLeast(1) to scaleRow.roundToInt().coerceAtLeast(1)
    }

    private fun computeFftMagnitude(gray: FloatArray, w: Int, h: Int): FloatArray {
        // 2D FFT (separable row then column)
        val real = Array(h) { y -> DoubleArray(w) { x -> gray[y * w + x].toDouble() } }
        val imag = Array(h) { DoubleArray(w) }
        for (y in 0 until h) fft(real[y], imag[y], w)
        val colR = DoubleArray(h); val colI = DoubleArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) { colR[y] = real[y][x]; colI[y] = imag[y][x] }
            fft(colR, colI, h)
            for (y in 0 until h) { real[y][x] = colR[y]; imag[y][x] = colI[y] }
        }
        // fftshift + 1 - log1p(|F|)
        val mag = FloatArray(h * w)
        for (fy in 0 until h) for (fx in 0 until w) {
            val sx = (fx + w / 2) % w; val sy = (fy + h / 2) % h
            val m = sqrt(real[sy][sx] * real[sy][sx] + imag[sy][sx] * imag[sy][sx])
            mag[fy * w + fx] = (1.0 - ln(1.0 + m)).toFloat()
        }
        return normalizeMinMax(mag)
    }

    private fun estimateGridGradient(gray: FloatArray, w: Int, h: Int): Pair<Int, Int>? {
        val (gx, gy) = sobelXY(gray, w, h)
        val gradXSum = FloatArray(w) { x -> var s = 0f; for (y in 0 until h) s += abs(gx[y * w + x]); s }
        val gradYSum = FloatArray(h) { y -> var s = 0f; for (x in 0 until w) s += abs(gy[y * w + x]); s }
        fun peaks(arr: FloatArray, thr: Float): List<Int> {
            val out = mutableListOf<Int>()
            val t = thr * (arr.maxOrNull() ?: 0f)
            for (i in 1 until arr.size - 1)
                if (arr[i] > arr[i - 1] && arr[i] > arr[i + 1] && arr[i] >= t && (out.isEmpty() || i - out.last() >= 4)) out += i
            return out
        }
        val px = peaks(gradXSum, .2f); val py = peaks(gradYSum, .2f)
        if (px.size < 4 || py.size < 4) return null
        fun medianInterval(p: List<Int>): Float {
            val diffs = (1 until p.size).map { (p[it] - p[it - 1]).toFloat() }.sorted()
            return diffs[diffs.size / 2]
        }
        val sx = (w / medianInterval(px)).roundToInt().coerceAtLeast(1)
        val sy = (h / medianInterval(py)).roundToInt().coerceAtLeast(1)
        return sx to sy
    }

    // ── grid refinement ───────────────────────────────────────────────────────

    private fun refineGrids(gray: FloatArray, w: Int, h: Int, gridX: Int, gridY: Int, refineIntensity: Float): Pair<IntArray, IntArray> {
        val (gx, gy) = sobelXY(gray, w, h)
        val gradXSum = FloatArray(w) { x -> var s = 0f; for (y in 0 until h) s += abs(gx[y * w + x]); s }
        val gradYSum = FloatArray(h) { y -> var s = 0f; for (x in 0 until w) s += abs(gy[y * w + x]); s }
        val cellW = w.toFloat() / gridX; val cellH = h.toFloat() / gridY
        fun collectCoords(size: Int, total: Int, grad: FloatArray, cellSize: Float): IntArray {
            val coords = mutableListOf<Int>()
            var pos = findBestGrid(total / 2.0, cellSize.toDouble(), cellSize.toDouble(), grad).toDouble()
            while (pos < total + cellSize / 2) {
                coords += findBestGrid(pos, cellSize * refineIntensity.toDouble(), cellSize * refineIntensity.toDouble(), grad)
                pos += cellSize
            }
            pos = findBestGrid(total / 2.0, cellSize.toDouble(), cellSize.toDouble(), grad).toDouble() - cellSize
            while (pos > -cellSize / 2) {
                coords += findBestGrid(pos, cellSize * refineIntensity.toDouble(), cellSize * refineIntensity.toDouble(), grad)
                pos -= cellSize
            }
            return coords.toSortedSet().toIntArray()
        }
        return collectCoords(gridX, w, gradXSum, cellW) to collectCoords(gridY, h, gradYSum, cellH)
    }

    private fun findBestGrid(origin: Double, rangeMin: Double, rangeMax: Double, grad: FloatArray): Int {
        val best = origin.roundToInt()
        val mx = grad.maxOrNull() ?: 0f
        if (mx < 1e-6f) return best
        var bestVal = -1f; var bestIdx = best
        val lo = (origin - rangeMin).roundToInt(); val hi = (origin + rangeMax).roundToInt()
        for (i in lo..hi) {
            if (i <= 0 || i >= grad.size - 1) continue
            if (grad[i] > grad[i - 1] && grad[i] > grad[i + 1] && grad[i] > bestVal) {
                bestVal = grad[i]; bestIdx = i
            }
        }
        return bestIdx
    }

    // ── sampling ──────────────────────────────────────────────────────────────

    private fun sampleCenter(img: FloatArray, w: Int, h: Int, xs: IntArray, ys: IntArray): FloatArray {
        val nx = xs.size - 1; val ny = ys.size - 1
        val out = FloatArray(ny * nx * 3)
        for (j in 0 until ny) {
            val cy = ((ys[j] + ys[j + 1]) * 0.5).toInt().coerceIn(0, h - 1)
            for (i in 0 until nx) {
                val cx = ((xs[i] + xs[i + 1]) * 0.5).toInt().coerceIn(0, w - 1)
                val src = (cy * w + cx) * 3
                out[(j * nx + i) * 3] = img[src]
                out[(j * nx + i) * 3 + 1] = img[src + 1]
                out[(j * nx + i) * 3 + 2] = img[src + 2]
            }
        }
        return out
    }

    private fun sampleCenterAlpha(alpha: FloatArray, w: Int, h: Int, xs: IntArray, ys: IntArray): FloatArray {
        val nx = xs.size - 1; val ny = ys.size - 1
        val out = FloatArray(ny * nx)
        for (j in 0 until ny) {
            val cy = ((ys[j] + ys[j + 1]) * 0.5).toInt().coerceIn(0, h - 1)
            for (i in 0 until nx) {
                val cx = ((xs[i] + xs[i + 1]) * 0.5).toInt().coerceIn(0, w - 1)
                out[j * nx + i] = alpha[cy * w + cx]
            }
        }
        return out
    }

    private fun sampleMedian(img: FloatArray, w: Int, h: Int, xs: IntArray, ys: IntArray): FloatArray {
        val nx = xs.size - 1; val ny = ys.size - 1
        val out = FloatArray(ny * nx * 3)
        for (j in 0 until ny) {
            val y0 = ys[j].coerceIn(0, h); val y1 = ys[j + 1].coerceIn(y0 + 1, h)
            for (i in 0 until nx) {
                val x0 = xs[i].coerceIn(0, w); val x1 = xs[i + 1].coerceIn(x0 + 1, w)
                val n = (y1 - y0) * (x1 - x0)
                val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
                var k = 0
                for (y in y0 until y1) for (x in x0 until x1) {
                    val s = (y * w + x) * 3; r[k] = img[s]; g[k] = img[s + 1]; b[k] = img[s + 2]; k++
                }
                r.sort(); g.sort(); b.sort()
                val m = n / 2
                out[(j * nx + i) * 3] = r[m]; out[(j * nx + i) * 3 + 1] = g[m]; out[(j * nx + i) * 3 + 2] = b[m]
            }
        }
        return out
    }

    private fun sampleMedianAlpha(alpha: FloatArray, w: Int, h: Int, xs: IntArray, ys: IntArray): FloatArray {
        val nx = xs.size - 1; val ny = ys.size - 1
        val out = FloatArray(ny * nx)
        for (j in 0 until ny) {
            val y0 = ys[j].coerceIn(0, h); val y1 = ys[j + 1].coerceIn(y0 + 1, h)
            for (i in 0 until nx) {
                val x0 = xs[i].coerceIn(0, w); val x1 = xs[i + 1].coerceIn(x0 + 1, w)
                val n = (y1 - y0) * (x1 - x0)
                val a = FloatArray(n)
                var k = 0
                for (y in y0 until y1) for (x in x0 until x1) a[k++] = alpha[y * w + x]
                a.sort()
                out[j * nx + i] = a[n / 2]
            }
        }
        return out
    }

    private fun sampleMajority(img: FloatArray, w: Int, h: Int, xs: IntArray, ys: IntArray): FloatArray {
        val nx = xs.size - 1; val ny = ys.size - 1
        val out = FloatArray(ny * nx * 3)
        for (j in 0 until ny) {
            val y0 = ys[j].coerceIn(0, h); val y1 = ys[j + 1].coerceIn(y0 + 1, h)
            for (i in 0 until nx) {
                val x0 = xs[i].coerceIn(0, w); val x1 = xs[i + 1].coerceIn(x0 + 1, w)
                val cell = ArrayList<FloatArray>()
                for (y in y0 until y1) for (x in x0 until x1) {
                    val s = (y * w + x) * 3; cell.add(floatArrayOf(img[s], img[s + 1], img[s + 2]))
                }
                val result = kmeans2(cell)
                out[(j * nx + i) * 3] = result[0]; out[(j * nx + i) * 3 + 1] = result[1]; out[(j * nx + i) * 3 + 2] = result[2]
            }
        }
        return out
    }

    private fun sampleMajorityAlpha(alpha: FloatArray, w: Int, h: Int, xs: IntArray, ys: IntArray): FloatArray {
        val nx = xs.size - 1; val ny = ys.size - 1
        val out = FloatArray(ny * nx)
        for (j in 0 until ny) {
            val y0 = ys[j].coerceIn(0, h); val y1 = ys[j + 1].coerceIn(y0 + 1, h)
            for (i in 0 until nx) {
                val x0 = xs[i].coerceIn(0, w); val x1 = xs[i + 1].coerceIn(x0 + 1, w)
                var opaque = 0
                var total = 0
                for (y in y0 until y1) for (x in x0 until x1) {
                    total++
                    if (alpha[y * w + x] > 0f) opaque++
                }
                out[j * nx + i] = if (opaque * 2 >= total) 255f else 0f
            }
        }
        return out
    }

    private fun kmeans2(cell: List<FloatArray>): FloatArray {
        if (cell.isEmpty()) return floatArrayOf(0f, 0f, 0f)
        if (cell.size == 1) return cell[0]
        var c0 = cell[0].copyOf()
        var c1 = cell[cell.indices.maxByOrNull { dist3(cell[it], c0) }!!].copyOf()
        repeat(6) {
            val s0 = FloatArray(3); val s1 = FloatArray(3); var n0 = 0; var n1 = 0
            for (p in cell) {
                if (dist3(p, c0) <= dist3(p, c1)) { for (c in 0..2) s0[c] += p[c]; n0++ }
                else { for (c in 0..2) s1[c] += p[c]; n1++ }
            }
            if (n0 > 0) for (c in 0..2) c0[c] = s0[c] / n0
            if (n1 > 0) for (c in 0..2) c1[c] = s1[c] / n1
        }
        var count1 = 0
        for (p in cell) if (dist3(p, c1) < dist3(p, c0)) count1++
        return if (count1 >= cell.size - count1) c1 else c0
    }

    private fun dist3(a: FloatArray, b: FloatArray): Float = (a[0]-b[0]).let { it*it } + (a[1]-b[1]).let { it*it } + (a[2]-b[2]).let { it*it }

    // ── peak detection (matches original detect_peak) ─────────────────────────

    private fun detectPeak(proj: FloatArray, peakWidth: Int, relThr: Float = .35f, minDist: Int = 6): Float? {
        val center = proj.size / 2
        val mx = proj.maxOrNull() ?: 0f
        if (mx < 1e-6f) return null
        val thr = mx * relThr
        data class Candidate(val index: Int, val score: Float)
        val candidates = mutableListOf<Candidate>()
        for (i in 1 until proj.size - 1) {
            var isPeak = true
            for (j in 1 until peakWidth) {
                val li = i - j; val ri = i + j
                if (li < 0 || ri >= proj.size) continue
                if (proj[li + 1] < proj[li] || proj[ri - 1] < proj[ri]) { isPeak = false; break }
            }
            if (!isPeak || proj[i] < thr) continue
            var leftClimb = 0f
            for (k in i downTo 1) { if (proj[k] > proj[k - 1]) leftClimb = abs(proj[i] - proj[k - 1]) else break }
            var rightFall = 0f
            for (k in i until proj.size - 1) { if (proj[k] > proj[k + 1]) rightFall = abs(proj[i] - proj[k + 1]) else break }
            candidates += Candidate(i, max(leftClimb, rightFall))
        }
        if (candidates.isEmpty()) return null
        val left  = candidates.filter { it.index < center - minDist && it.index > center * .25 }.sortedByDescending { it.score }
        val right = candidates.filter { it.index > center + minDist && it.index < center * 1.75 }.sortedByDescending { it.score }
        if (left.isEmpty() || right.isEmpty()) return null
        return abs(right[0].index - left[0].index) / 2f
    }

    // ── 1D Gaussian smooth ────────────────────────────────────────────────────

    private fun smooth1d(v: FloatArray, k: Int = 17): FloatArray {
        var kk = if (k < 3) return v else k
        if (kk % 2 == 0) kk++
        val sigma = kk / 6.0
        val half = kk / 2
        val ker = FloatArray(kk) { i -> val d = (i - half).toDouble(); exp(-d * d / (2 * sigma * sigma)).toFloat() }
        val ksum = ker.sum()
        for (i in ker.indices) ker[i] /= ksum
        val out = FloatArray(v.size)
        for (i in v.indices) {
            var s = 0f
            for (ki in 0 until kk) { val idx = (i - half + ki).coerceIn(0, v.size - 1); s += ker[ki] * v[idx] }
            out[i] = s
        }
        return out
    }

    // ── Sobel ─────────────────────────────────────────────────────────────────

    private fun sobelXY(gray: FloatArray, w: Int, h: Int): Pair<FloatArray, FloatArray> {
        val kx = intArrayOf(-1,0,1,-2,0,2,-1,0,1)
        val ky = intArrayOf(-1,-2,-1,0,0,0,1,2,1)
        val gx = FloatArray(w * h); val gy = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var ax = 0f; var ay = 0f
            for (j in -1..1) for (i in -1..1) {
                val xx = (x + i).coerceIn(0, w - 1); val yy = (y + j).coerceIn(0, h - 1)
                val p = gray[yy * w + xx]; val ki = (j + 1) * 3 + i + 1
                ax += p * kx[ki]; ay += p * ky[ki]
            }
            gx[y * w + x] = ax; gy[y * w + x] = ay
        }
        return gx to gy
    }

    // ── utilities ─────────────────────────────────────────────────────────────

    private fun normalizeMinMax(v: FloatArray): FloatArray {
        val lo = v.minOrNull() ?: 0f; val hi = v.maxOrNull() ?: 0f
        return if (hi - lo < 1e-8f) FloatArray(v.size) else FloatArray(v.size) { (v[it] - lo) / (hi - lo) }
    }

    // ── FFT (radix-2 for powers of 2, Bluestein for arbitrary size) ──────────

    private fun fft(real: DoubleArray, imag: DoubleArray, size: Int) {
        if (size == 1) return
        if (size and (size - 1) == 0) radix2Fft(real, imag, size) else bluesteinFft(real, imag, size)
    }

    private fun radix2Fft(real: DoubleArray, imag: DoubleArray, size: Int) {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val r = real[i]; real[i] = real[j]; real[j] = r; val im = imag[i]; imag[i] = imag[j]; imag[j] = im }
        }
        var len = 2
        while (len <= size) {
            val ang = -2.0 * PI / len; val wR = cos(ang); val wI = sin(ang)
            for (s in 0 until size step len) {
                var cR = 1.0; var cI = 0.0
                for (off in 0 until len / 2) {
                    val l = s + off; val r = l + len / 2
                    val pR = real[r] * cR - imag[r] * cI; val pI = real[r] * cI + imag[r] * cR
                    val lR = real[l]; val lI = imag[l]
                    real[l] = lR + pR; imag[l] = lI + pI; real[r] = lR - pR; imag[r] = lI - pI
                    val nR = cR * wR - cI * wI; cI = cR * wI + cI * wR; cR = nR
                }
            }
            len = len shl 1
        }
    }

    private fun bluesteinFft(real: DoubleArray, imag: DoubleArray, size: Int) {
        val n = nextPow2(size * 2 - 1)
        val aR = DoubleArray(n); val aI = DoubleArray(n); val bR = DoubleArray(n); val bI = DoubleArray(n)
        for (i in 0 until size) {
            val a = PI * i.toLong() * i / size; val c = cos(a); val s = sin(a)
            aR[i] = real[i] * c + imag[i] * s; aI[i] = imag[i] * c - real[i] * s
            bR[i] = c; bI[i] = s
            if (i != 0) { bR[n - i] = c; bI[n - i] = s }
        }
        radix2Fft(aR, aI, n); radix2Fft(bR, bI, n)
        for (i in 0 until n) { val r = aR[i]*bR[i]-aI[i]*bI[i]; val im = aR[i]*bI[i]+aI[i]*bR[i]; aR[i]=r; aI[i]=im }
        for (i in 0 until n) aI[i] = -aI[i]
        radix2Fft(aR, aI, n)
        for (i in 0 until n) { aR[i] /= n; aI[i] = -aI[i] / n }
        for (i in 0 until size) {
            val a = PI * i.toLong() * i / size; val c = cos(a); val s = sin(a)
            real[i] = aR[i] * c + aI[i] * s; imag[i] = aI[i] * c - aR[i] * s
        }
    }

    private fun nextPow2(v: Int): Int { var r = 1; while (r < v) r = r shl 1; return r }
}
