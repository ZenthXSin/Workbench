package io.github.zenthxsin.rustwright.ui.codex

import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.scene.Element
import arc.scene.event.ClickListener
import arc.scene.event.Touchable
import arc.scene.ui.Label
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp

/**
 * 把网页 CSS 直接翻译成 Arc 的小工具：数值都是 CSS px（= dp），颜色取 RwTheme 里对应的 chatgpt 令牌。
 * 对照来源：new-api web/src/features/codex（chatgpt.css 与各组件的 Tailwind 类）。
 */
internal object Css {
    val text get() = RwTheme.Colors.text
    val muted get() = RwTheme.Colors.muted
    val subtle get() = RwTheme.Colors.subtle
    val border get() = RwTheme.Colors.outline
    val hover get() = RwTheme.Colors.surface2
    val selected get() = RwTheme.Colors.surface3
    val bg get() = RwTheme.Colors.bg
    val blueStop get() = RwTheme.Colors.blue
    val green: Color get() = if (RwTheme.dark) Color.valueOf("46bf72") else Color.valueOf("1e8a3e")

    /** `font-size/line-height` + 字重 + 颜色。 */
    fun label(text: String, px: Float, color: Color = this.text, weight: Int = 400, lineHeight: Float? = null) =
        Label(text, Label.LabelStyle(RwTheme.text(px, weight, lineHeight), color))

    /** 换行段落。 */
    fun para(text: String, px: Float, color: Color = this.text, weight: Int = 400, lineHeight: Float? = null) =
        label(text, px, color, weight, lineHeight).apply { setWrap(true) }

    /** Lucide 图标（[px] 为 size-*）：按字形实际像素居中在 px×px 方块里。 */
    fun icon(glyph: Char, px: Float, color: Color = this.text) = Icon(glyph, px, color)

    /** 文本宽度（不排版）。 */
    fun measure(font: arc.graphics.g2d.Font, s: String): Float {
        val layout = arc.graphics.g2d.GlyphLayout.obtain()
        layout.setText(font, s)
        val w = layout.width
        layout.free()
        return w
    }

    /** 超出 [max] 宽度时截断并加「…」（等价于 CSS truncate，用在不撑满的单元格里）。 */
    fun fit(text: String, font: arc.graphics.g2d.Font, max: Float): String {
        if (measure(font, text) <= max) return text
        var end = text.length
        while (end > 0 && measure(font, text.substring(0, end) + "...") > max) end--
        return text.substring(0, end) + "..."
    }

    /** 圆角描边块：`border` + `border-radius` + `background`。 */
    fun box(fill: Color = bg, stroke: Color? = border, radius: Int) =
        if (stroke == null) RwTheme.round(radius, fill) else RwTheme.card(fill, stroke, radius)

    /**
     * 可点的透明按钮（hover / 按下显示浅灰底），尺寸由调用方 cell 决定。
     * 返回的 Table 里放内容；[radius] 为圆角，999 表示胶囊 / 圆形。
     */
    fun ghost(radius: Int, onClick: (Table) -> Unit): Table = Table().apply {
        touchable = Touchable.enabled
        val pressed = RwTheme.round(radius.coerceAtMost(40), hover)
        lateinit var click: ClickListener
        click = clicked { onClick(this) }
        update { background(if (click.isPressed) pressed else null) }
    }

    /** 图标按钮：`size-*` 方块 + 居中的 Lucide 图标。 */
    fun iconButton(glyph: Char, iconPx: Float, color: Color = text, radius: Int = 12, name: String? = null, onClick: (Table) -> Unit) =
        ghost(radius, onClick).apply {
            this.name = name
            add(icon(glyph, iconPx, color))
        }

    /** 胶囊按钮：拒绝（描边）/ 允许（实心主色），h36 px16 14px。 */
    fun pill(text: String, primary: Boolean, onClick: () -> Unit) = Table(
        if (primary) RwTheme.round(18, RwTheme.Colors.accent) else RwTheme.card(bg, border, 18)
    ).apply {
        name = text
        touchable = Touchable.enabled
        margin(0f, dp(16f), 0f, dp(16f))
        add(label(text, 14f, if (primary) RwTheme.Colors.onAccent else this@Css.text))
        clicked { onClick() }
    }

    /** 1px 分隔线。 */
    fun rule(color: Color = border) = arc.scene.ui.Image(RwTheme.solid(color))
}

/** 图标元素：字形纹理按像素边界居中，颜色可改（style.fontColor 兼容旧写法）。 */
internal class Icon(glyph: Char, private val px: Float, color: Color) : Element() {
    val style = Label.LabelStyle(RwTheme.lucide(px), Color(color))
    private var glyph = glyph
    fun setText(text: String) { if (text.isNotEmpty()) glyph = text[0] }
    fun setText(c: Char) { glyph = c }
    init { touchable = Touchable.disabled }
    override fun getPrefWidth() = dp(px)
    override fun getPrefHeight() = dp(px)
    private var prepared = 0.toChar()
    override fun draw() {
        val font = style.font
        // 增量字体：排版一次才会把新字形写进纹理
        if (prepared != glyph) { arc.graphics.g2d.GlyphLayout.obtain().apply { setText(font, glyph.toString()) }.free(); prepared = glyph }
        val g = font.data.getGlyph(glyph) ?: return
        val region = font.getRegion(g.page)
        val sx = font.data.scaleX; val sy = font.data.scaleY
        val w = g.width * sx; val h = g.height * sy
        val c = style.fontColor
        Draw.color(c.r, c.g, c.b, c.a * parentAlpha * color.a)
        val tr = arc.graphics.g2d.TextureRegion(region.texture)
        tr.set(g.u, g.v2, g.u2, g.v)
        Draw.rect(tr, x + width / 2, y + height / 2, w, h)
        Draw.reset()
    }
}

/** 侧栏背景：160° 线性渐变（网页 .chatgpt-sidebar）。 */
internal class GradientDrawable(private val from: () -> Color, private val to: () -> Color) : arc.scene.style.BaseDrawable() {
    override fun draw(x: Float, y: Float, width: Float, height: Float) {
        val a = from(); val b = to()
        val c1 = Color(a).mul(Draw.getColor()).toFloatBits()
        val c2 = Color(b).mul(Draw.getColor()).toFloatBits()
        val mid = Color(a).lerp(b, 0.5f).mul(Draw.getColor()).toFloatBits()
        // 160°：左上 → 右下，近似为左上 from、右下 to、其余两角取中间
        Fill.quad(x, y, mid, x + width, y, c2, x + width, y + height, mid, x, y + height, c1)
    }
}

internal fun Element.named(n: String) = apply { name = n }
