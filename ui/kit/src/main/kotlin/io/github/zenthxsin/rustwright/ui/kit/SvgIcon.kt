package io.github.zenthxsin.rustwright.ui.kit

import arc.graphics.Color
import arc.graphics.Pixmap
import arc.graphics.Texture
import arc.graphics.g2d.Draw
import arc.graphics.g2d.TextureRegion
import arc.scene.Element
import arc.scene.event.Touchable
import arc.util.Tmp
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 原型里的 svg 线条图标（24×24 视框、round 线帽与拐角），按实际像素大小直接光栅化：
 * 每个像素到折线的距离决定覆盖率，和浏览器描边的观感一致。白色贴图 + [tint] 着色，按「路径 / 像素 / 线宽」缓存。
 */
class SvgIcon(d: String, private val px: Float, color: Color = K.text, private val stroke: Float = 2f) : Element() {
    val tint = Color(color)
    var path: String = d
        set(v) {
            if (field != v) {
                field = v
                region = null
            }
        }
    private var region: TextureRegion? = null

    init {
        touchable = Touchable.disabled
    }

    override fun getPrefWidth() = dp(px)
    override fun getPrefHeight() = dp(px)

    override fun draw() {
        val n = max(1, round(dp(px)).toInt())
        // 没栅格化过的图标在后台算像素、下一帧再画：一个图标在手机上要几毫秒，侧栏 / 菜单一次冒出十来个新图标会卡一下
        val r = region ?: (cached(path, n, stroke) ?: run { requestRaster(path, n, stroke); return }).also { region = it }
        Draw.color(tint.r, tint.g, tint.b, tint.a * parentAlpha * color.a)
        // 按图标自身大小画在元素中心，贴齐像素免得发虚
        val w = r.width.toFloat() * width / dp(px)
        val h = r.height.toFloat() * height / dp(px)
        val sx = round(x + (width - w) / 2f)
        val sy = round(y + (height - h) / 2f)
        Draw.rect(r, sx + w / 2f, sy + h / 2f, w, h, rotation)
        Draw.reset()
    }

    companion object {
        private val cache = HashMap<String, TextureRegion>()

        private val pendingRaster = HashSet<String>()
        private val rasterPool = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "svg-raster").apply { isDaemon = true } }

        fun cached(d: String, n: Int, stroke: Float): TextureRegion? = cache["$n/$stroke/$d"]

        /** 后台算覆盖率，回 GL 线程建纹理；没有应用循环（单测）时直接同步做。 */
        fun requestRaster(d: String, n: Int, stroke: Float) {
            val key = "$n/$stroke/$d"
            if (key in cache || !pendingRaster.add(key)) return
            val app = arc.Core.app
            if (app == null) { pendingRaster.remove(key); raster(d, n, stroke); return }
            rasterPool.execute {
                val pm = runCatching { pixels(d, n, stroke) }.getOrNull()
                app.post {
                    pendingRaster.remove(key)
                    if (pm == null || key in cache) { pm?.dispose(); return@post }
                    cache[key] = upload(pm)
                    arc.Core.graphics?.requestRendering()
                }
            }
        }

        private fun upload(pm: Pixmap): TextureRegion {
            val t = Texture(pm)
            t.setFilter(Texture.TextureFilter.linear)
            pm.dispose()
            return TextureRegion(t)
        }

        fun raster(d: String, n: Int, stroke: Float): TextureRegion {
            val key = "$n/$stroke/$d"
            cache[key]?.let { return it }
            return upload(pixels(d, n, stroke)).also { cache[key] = it }
        }

        private fun pixels(d: String, n: Int, stroke: Float): Pixmap {
            val segs = SvgPath.segments(d)
            val scale = n / 24f
            val hw = stroke / 2f
            val pm = Pixmap(n, n)
            val px = arc.graphics.Color()  // 后台线程用：不能碰共享的 Tmp.c1
            for (py in 0 until n) for (pxi in 0 until n) {
                // 像素中心换算到视框坐标（Pixmap 第 0 行在上，与 svg 一致）
                val vx = (pxi + 0.5f) / scale
                val vy = (py + 0.5f) / scale
                var best = Float.MAX_VALUE
                var i = 0
                while (i < segs.size) {
                    val dd = segDist2(vx, vy, segs[i], segs[i + 1], segs[i + 2], segs[i + 3])
                    if (dd < best) best = dd
                    i += 4
                }
                val cov = ((hw - sqrt(best)) * scale + 0.5f).coerceIn(0f, 1f)
                if (cov > 0f) pm.set(pxi, py, px.set(1f, 1f, 1f, cov).rgba())
            }
            return pm
        }

        private fun segDist2(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x2 - x1
            val dy = y2 - y1
            val l2 = dx * dx + dy * dy
            var t = if (l2 > 0f) ((px - x1) * dx + (py - y1) * dy) / l2 else 0f
            t = t.coerceIn(0f, 1f)
            val cx = x1 + t * dx - px
            val cy = y1 + t * dy - py
            return cx * cx + cy * cy
        }
    }
}

/** 最小 svg 路径解析：M L H V C S Q T A Z（含相对写法），曲线压平成线段，输出 [x1,y1,x2,y2,…]。 */
internal object SvgPath {
    fun segments(d: String): FloatArray {
        val out = ArrayList<Float>()
        val tok = tokens(d)
        var i = 0
        var cmd = 'M'
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        var lcx = 0f
        var lcy = 0f // 上一条曲线的控制点（S / T 用）
        var lastCmd = ' '
        fun num(): Float = (tok[i++] as Float)
        fun line(x: Float, y: Float) {
            out += cx; out += cy; out += x; out += y
            cx = x; cy = y
        }
        fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
            val steps = 16
            val x0 = cx
            val y0 = cy
            for (k in 1..steps) {
                val t = k / steps.toFloat()
                val mt = 1 - t
                val px = mt * mt * mt * x0 + 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t * t * t * x
                val py = mt * mt * mt * y0 + 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t * t * t * y
                line(px, py)
            }
        }
        while (i < tok.size) {
            val t = tok[i]
            if (t is Char) {
                cmd = t
                i++
                if (cmd == 'Z' || cmd == 'z') {
                    if (cx != sx || cy != sy) line(sx, sy)
                    cx = sx; cy = sy
                    lastCmd = cmd
                    continue
                }
            }
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f
            val oy = if (rel) cy else 0f
            when (cmd.uppercaseChar()) {
                'M' -> {
                    cx = ox + num(); cy = oy + num()
                    sx = cx; sy = cy
                    // M 后面连着的坐标按 L 处理
                    cmd = if (rel) 'l' else 'L'
                    // 单独一个 M 不画东西；但「M x y h.01」这种点由后续 L/H 画出
                }
                'L' -> line(ox + num(), oy + num())
                'H' -> line((if (rel) cx else 0f) + num(), cy)
                'V' -> line(cx, (if (rel) cy else 0f) + num())
                'C' -> {
                    val x1 = ox + num(); val y1 = oy + num(); val x2 = ox + num(); val y2 = oy + num(); val x = ox + num(); val y = oy + num()
                    cubic(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2
                }
                'S' -> {
                    val prevCurve = lastCmd.uppercaseChar() == 'C' || lastCmd.uppercaseChar() == 'S'
                    val x1 = if (prevCurve) 2 * cx - lcx else cx
                    val y1 = if (prevCurve) 2 * cy - lcy else cy
                    val x2 = ox + num(); val y2 = oy + num(); val x = ox + num(); val y = oy + num()
                    cubic(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2
                }
                'Q' -> {
                    val qx = ox + num(); val qy = oy + num(); val x = ox + num(); val y = oy + num()
                    cubic(cx + 2f / 3f * (qx - cx), cy + 2f / 3f * (qy - cy), x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y), x, y)
                    lcx = qx; lcy = qy
                }
                'T' -> {
                    val prevQ = lastCmd.uppercaseChar() == 'Q' || lastCmd.uppercaseChar() == 'T'
                    val qx = if (prevQ) 2 * cx - lcx else cx
                    val qy = if (prevQ) 2 * cy - lcy else cy
                    val x = ox + num(); val y = oy + num()
                    cubic(cx + 2f / 3f * (qx - cx), cy + 2f / 3f * (qy - cy), x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y), x, y)
                    lcx = qx; lcy = qy
                }
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num(); val large = num() != 0f; val sweep = num() != 0f
                    val x = ox + num(); val y = oy + num()
                    arc(cx, cy, rx, ry, rot, large, sweep, x, y) { px, py -> line(px, py) }
                }
            }
            lastCmd = cmd
        }
        return out.toFloatArray()
    }

    /** svg 端点式椭圆弧 → 中心式，按角度压平。 */
    private fun arc(x1: Float, y1: Float, rx0: Float, ry0: Float, rotDeg: Float, large: Boolean, sweep: Boolean, x2: Float, y2: Float, emit: (Float, Float) -> Unit) {
        if (rx0 == 0f || ry0 == 0f) { emit(x2, y2); return }
        var rx = abs(rx0)
        var ry = abs(ry0)
        val phi = Math.toRadians(rotDeg.toDouble())
        val cp = cos(phi)
        val sp = sin(phi)
        val dx = (x1 - x2) / 2.0
        val dy = (y1 - y2) / 2.0
        val x1p = cp * dx + sp * dy
        val y1p = -sp * dx + cp * dy
        val lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lam > 1) { val s = sqrt(lam).toFloat(); rx *= s; ry *= s }
        val num = rx.toDouble() * rx * ry * ry - rx.toDouble() * rx * y1p * y1p - ry.toDouble() * ry * x1p * x1p
        val den = rx.toDouble() * rx * y1p * y1p + ry.toDouble() * ry * x1p * x1p
        var co = sqrt(max(0.0, num / den))
        if (large == sweep) co = -co
        val cxp = co * rx * y1p / ry
        val cyp = -co * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2.0
        val cy = sp * cxp + cp * cyp + (y1 + y2) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            var a = acos((dot / len).coerceIn(-1.0, 1.0))
            if (ux * vy - uy * vx < 0) a = -a
            return a
        }
        val th1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dth > 0) dth -= 2 * Math.PI
        if (sweep && dth < 0) dth += 2 * Math.PI
        val steps = max(4, ceil(abs(dth) / (Math.PI / 24)).toInt())
        for (k in 1..steps) {
            val th = th1 + dth * k / steps
            val ex = rx * cos(th)
            val ey = ry * sin(th)
            emit((cp * ex - sp * ey + cx).toFloat(), (sp * ex + cp * ey + cy).toFloat())
        }
    }

    /** 拆成命令字母和数字（支持 1.5.5、-1-2、1e-3 这类紧凑写法）。 */
    private fun tokens(d: String): List<Any> {
        val out = ArrayList<Any>()
        var i = 0
        while (i < d.length) {
            val c = d[i]
            when {
                c.isLetter() && c != 'e' && c != 'E' -> { out += c; i++ }
                c == '-' || c == '+' || c == '.' || c.isDigit() -> {
                    val start = i
                    i++
                    var dot = c == '.'
                    while (i < d.length) {
                        val ch = d[i]
                        if (ch.isDigit()) i++
                        else if (ch == '.' && !dot) { dot = true; i++ }
                        else if ((ch == 'e' || ch == 'E') && i + 1 < d.length) { i++; if (d[i] == '-' || d[i] == '+') i++ }
                        else break
                    }
                    out += d.substring(start, i).toFloat()
                }
                else -> i++
            }
        }
        // 数字后面紧跟的 0/1 标志（A 命令里「0 0 1」可能写成「001」）这里不处理，原型里没有这种写法
        return out
    }
}
