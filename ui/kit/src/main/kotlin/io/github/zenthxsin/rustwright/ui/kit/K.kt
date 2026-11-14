package io.github.zenthxsin.rustwright.ui.kit

import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.GlyphLayout
import arc.graphics.g2d.Shapes
import arc.scene.Element
import arc.scene.event.Touchable
import arc.scene.style.ShapeDrawable
import arc.scene.ui.Label
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp

/**
 * 融合版界面的设计令牌与基础构件，数值来自原型（App.dc.html，CSS px = dp）。
 * 深色一套照原型；浅色一套按同样的层次（底 → 卡片 → 按下 → 描边）配出来，切换时原地改值后整页重建。
 */
object K {
    // ---- 颜色（对象不变，切换深浅时原地改值） ----
    val bg = Color()
    val drawerBg = Color()
    val surface = Color()
    val surface2 = Color()
    val surface3 = Color()
    val input = Color()
    val inputBorder = Color()
    val line = Color()
    val cardBorder = Color()
    val divider = Color()
    val bubble = Color()
    val text = Color()
    val muted = Color()
    val subtle = Color()
    val dim = Color()
    val accent = Color()
    val accentText = Color()
    val accentLight = Color()
    val light = Color()
    val onLight = Color()
    val green = Color()
    val amber = Color()
    val blue = Color()
    val ringTrack = Color()
    val danger = Color()
    val scrim = Color(0f, 0f, 0f, 0.55f)

    private val names = arrayOf(bg, drawerBg, surface, surface2, surface3, input, inputBorder, line, cardBorder, divider, bubble, text, muted, subtle, dim, accent, accentText, accentLight, light, onLight, green, amber, blue, ringTrack, danger)
    private val darkHex = arrayOf("181817", "121211", "232322", "2A2A28", "2E2E2C", "1F1F1E", "3A3A38", "333331", "2F2F2D", "2A2A28", "0E0E0D", "ECEBE6", "A3A29B", "8C8B85", "6B6A65", "C2603A", "D9774F", "E9A07A", "F4F3EE", "181817", "6FBF73", "D9A74F", "4C8DF0", "2E3440", "E5866A")
    private val lightHex = arrayOf("F3F2EE", "EAE9E4", "FFFFFF", "F0EFEB", "E8E7E2", "FFFFFF", "D6D5CF", "DDDCD6", "E2E1DC", "E8E7E2", "E9E8E3", "1C1C1A", "5E5D58", "76756F", "9C9B95", "C2603A", "B5532E", "C2603A", "1C1C1A", "F7F6F2", "3E9446", "B07A1F", "2F6FD6", "D7DCE5", "C24E33")

    /** 按深浅填颜色；[RwTheme.applyMode] 时自动调用。 */
    fun applyPalette(isDark: Boolean) {
        val src = if (isDark) darkHex else lightHex
        names.forEachIndexed { i, c -> c.set(Color.valueOf(src[i])) }
        scrim.set(0f, 0f, 0f, if (isDark) 0.55f else 0.32f)
    }

    init {
        applyPalette(RwTheme.dark)
        RwTheme.paletteListeners += { applyPalette(it) }
    }

    /** 深浅两套各给一个值（零散的点缀色用）。 */
    fun dl(darkHex: String, lightHex: String): Color = c(if (RwTheme.dark) darkHex else lightHex)

    fun c(hex: String, a: Float = 1f): Color = Color.valueOf(hex).a(a)

    // ---- 文字 ----

    /** 单行文字：[px] 字号、[weight] 字重、[lh] 行高（px，可空）。 */
    fun text(s: String, px: Float, color: Color = text, weight: Int = 400, lh: Float? = null): Label =
        Label(s, Label.LabelStyle(RwTheme.text(px, weight, lh ?: normal(px)), color))

    /** 浏览器 line-height: normal：Noto Sans SC 的 hhea 上升 + 下降 = 1.448em。 */
    fun normal(px: Float): Float = Math.round(px * 1.448f * 10f) / 10f

    /** 自动换行段落。 */
    fun para(s: String, px: Float, color: Color = text, weight: Int = 400, lh: Float? = null): Label =
        text(s, px, color, weight, lh).apply { setWrap(true) }

    /** 品牌衬线体（Newsreader），只用于拉丁字母。 */
    fun serif(s: String, px: Float, color: Color = text, weight: Int = 600, lh: Float = px): Label =
        Label(s, Label.LabelStyle(RwTheme.serif(px, weight, lh), color))

    /** 等宽文字。 */
    fun mono(s: String, px: Float, color: Color = subtle, lh: Float? = null): Label =
        Label(s, Label.LabelStyle(RwTheme.monoAt(px, lh), color))

    /** 原型 svg 图标（路径见 [PI]），[px] 为边长，[stroke] 为 stroke-width。 */
    fun svg(path: String, px: Float, color: Color = text, stroke: Float = 2f): SvgIcon = SvgIcon(path, px, color, stroke)

    /** Lucide 图标，[px] 为边长，[stroke] 为原型 svg 的 stroke-width。 */
    fun icon(glyph: Char, px: Float, color: Color = text, stroke: Float = 2f): Icon = Icon(glyph, px, color, stroke)

    fun measure(font: arc.graphics.g2d.Font, s: String): Float {
        val l = GlyphLayout.obtain()
        l.setText(font, s)
        val w = l.width
        l.free()
        return w
    }

    // ---- 背景 ----

    fun fill(color: Color, radius: Float) = ShapeDrawable(color, dp(radius))

    fun card(fill: Color = surface, radius: Float = 14f, border: Color? = cardBorder) =
        ShapeDrawable(fill, dp(radius)).apply { if (border != null) border(dp(1f), border) }

    fun round(fill: Color, tl: Float, tr: Float, br: Float, bl: Float) =
        ShapeDrawable().fill(fill).radius(dp(tl), dp(tr), dp(br), dp(bl))
}

/** Lucide 图标字形，按字形实际大小居中画在 px×px 方块里；颜色可随时改。 */
class Icon(var glyph: Char, private val px: Float, color: Color, stroke: Float = 2f) : Element() {
    private val font = RwTheme.lucide(px, stroke)
    val tint = Color(color)
    private var prepared = 0.toChar()

    init {
        touchable = Touchable.disabled
    }

    override fun getPrefWidth() = dp(px)
    override fun getPrefHeight() = dp(px)

    override fun draw() {
        // 增量字体：排版一次才会把字形写进纹理
        if (prepared != glyph) {
            GlyphLayout.obtain().apply { setText(font, glyph.toString()) }.free()
            prepared = glyph
        }
        val g = font.data.getGlyph(glyph) ?: return
        val region = font.getRegion(g.page)
        val w = g.width * font.data.scaleX
        val h = g.height * font.data.scaleY
        Draw.color(tint.r, tint.g, tint.b, tint.a * parentAlpha * color.a)
        val tr = arc.graphics.g2d.TextureRegion(region.texture)
        tr.set(g.u, g.v2, g.u2, g.v)
        Draw.rect(tr, x + width / 2f, y + height / 2f, w, h)
        Draw.reset()
    }
}

/** 原型里的菜单图标：三条长短递减的圆头横线（16 / 11 / 7）。 */
class MenuIcon(private val color: Color = K.text) : Element() {
    init {
        touchable = Touchable.disabled
    }

    override fun getPrefWidth() = dp(24f)
    override fun getPrefHeight() = dp(24f)

    override fun draw() {
        val s = width / 24f
        val t = 1.6f * s
        val c = Color(color).a(color.a * parentAlpha * this.color.a)
        val left = x + 4f * s
        for ((i, len) in listOf(16f, 11f, 7f).withIndex()) {
            val cy = y + height - (7f + i * 5f) * s
            Shapes.rect(left - t / 2f, cy - t / 2f, len * s + t, t, t / 2f, c)
        }
    }
}

/** 产品品牌标志（原型 svg 渲染成的白色贴图，按 [color] 着色）。 */
class AnvilLogo(private val px: Float, color: Color = K.accent) : Element() {
    val tint = Color(color)

    init {
        touchable = Touchable.disabled
    }

    override fun getPrefWidth() = dp(px)
    override fun getPrefHeight() = dp(px)

    override fun draw() {
        val resource = io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.markResource
        val r = resource?.let { region(it) }
        if (r == null) {
            Shapes.rect(x + width * 0.2f, y + height * 0.2f, width * 0.6f, height * 0.6f, dp(4f), Color(tint).a(tint.a * parentAlpha * color.a))
            return
        }
        Draw.color(tint.r, tint.g, tint.b, tint.a * parentAlpha * color.a)
        Draw.rect(r, x + width / 2f, y + height / 2f, width, height)
        Draw.reset()
    }

    companion object {
        private val regions = HashMap<String, arc.graphics.g2d.TextureRegion?>()
        private fun region(resource: String): arc.graphics.g2d.TextureRegion? = regions.getOrPut(resource) {
            runCatching {
                val bytes = io.github.zenthxsin.rustwright.core.runtime.Res.bytes(resource) ?: return@getOrPut null
                val texture = arc.graphics.Texture(arc.graphics.Pixmap(bytes))
                texture.setFilter(arc.graphics.Texture.TextureFilter.linear)
                arc.graphics.g2d.TextureRegion(texture)
            }.getOrNull()
        }
    }
}
