package io.github.zenthxsin.rustwright.fused

import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.graphics.g2d.Shapes
import arc.math.geom.Vec2
import arc.scene.Element
import arc.scene.Group
import arc.scene.event.EventListener
import arc.scene.event.Touchable
import arc.scene.ui.Label
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import arc.scene.ui.layout.WidgetGroup
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.Tap
import java.util.IdentityHashMap

/** 挂在可选文字的消息根节点 userObject 上：回复（长按直接选词）或用户气泡（长按先弹菜单）。 */
internal class Selectable(val bubble: Boolean, val text: String)

/**
 * 对话里的文字选区（参考系统文本选择）：长按回复选中手指下的一段字，两端橙色水滴拖柄可拖，
 * 选区上方浮「复制 / 全选」；点别处、换对话或返回时取消。范围是一条消息里的全部 Label。
 *
 * 坐标一律用 [scroll] 控件内坐标（视口坐标），每次现算，所以滚动时选区跟着内容走；
 * [overlay] 和 [menuLayer] 与 [scroll] 叠在同一个 Stack 里，原点相同。
 */
internal class ChatSelection(private val scroll: ScrollPane, private val copy: (String) -> Unit) {
    /** 一个字形：所在 Label、在整条消息纯文本里的位置、Label 内的横向范围和所在行的上下沿。 */
    private class G(val label: Label, val char: Int, val x: Float, val w: Float, val top: Float, val bottom: Float, val line: Int)

    private var root: Element? = null
    private val glyphs = ArrayList<G>()
    private var text = ""
    private var start = 0
    private var end = 0
    /** 正在拖的拖柄：1 起点，2 终点。 */
    var dragging = 0
        private set
    private val r get() = dp(10f)
    /** 拖拖柄时手指在文字下方一个拖柄高；长按后直接拖时手指就在文字上。 */
    private var lift = 0f
    private val tmp = Vec2()
    private val offsets = IdentityHashMap<Label, Vec2>()

    val active get() = root != null

    /** 画高亮和拖柄，叠在对话上面、不接触摸。 */
    val overlay = object : Element() {
        init { touchable = Touchable.disabled }
        override fun draw() = drawSelection(x, y, parentAlpha)
    }

    private val menu = Table(K.fill(K.c("333331"), 10f)).apply {
        margin(0f, dp(6f), 0f, dp(6f))
        add(menuButton("复制") { copySelection() })
        add(menuButton("全选") { root?.let { selectAll(it) } })
        visible = false
    }

    /** 放菜单的一层：只让菜单本身接触摸。 */
    val menuLayer = object : WidgetGroup() {
        init {
            touchable = Touchable.childrenOnly
            addChild(menu)
        }
        override fun act(delta: Float) {
            super.act(delta)
            layoutMenu()
        }
    }

    private fun menuButton(label: String, tap: () -> Unit) = Tap(null, 8f, tap).apply {
        name = label
        margin(0f, dp(14f), 0f, dp(14f))
        add(K.text(label, 15f, Color.white, lh = 15f)).padBottom(dp(2f))
        minH = dp(44f)
    }

    fun isMenu(e: Element?) = e != null && e.isDescendantOf(menu)

    /** 长按 [rootEl] 里视口坐标 ([vx], [vy]) 处：选中那里的一段字。 */
    fun begin(rootEl: Element, vx: Float, vy: Float) {
        root = rootEl
        collect()
        if (glyphs.isEmpty()) { clear(); return }
        val i = glyphAt(vx, vy)
        selectWord(i)
    }

    fun selectAll(rootEl: Element) {
        root = rootEl
        collect()
        if (glyphs.isEmpty()) { clear(); return }
        start = 0
        end = glyphs.lastIndex
    }

    /** 取消选区；本来就没有返回 false。 */
    fun clear(): Boolean {
        if (root == null) return false
        root = null
        glyphs.clear()
        dragging = 0
        menu.visible = false
        return true
    }

    /** 按下点在拖柄上就开始拖（返回 true）。 */
    fun grab(vx: Float, vy: Float): Boolean {
        if (!active) return false
        val reach = dp(26f)
        lift = 2f * r
        anchor(true)?.let { (ax, ay) -> if (dst(vx, vy, ax - r, ay - r) < reach) { dragging = 1; return true } }
        anchor(false)?.let { (bx, by) -> if (dst(vx, vy, bx + r, by - r) < reach) { dragging = 2; return true } }
        return false
    }

    /** 拖柄或长按后的手指移到 ([vx], [vy])；拖柄在文字下方，往上偏一点再找字。 */
    fun drag(vx: Float, vy: Float) {
        if (!active || dragging == 0) return
        val i = glyphAt(vx, vy + lift)
        if (dragging == 1) {
            if (i > end) { start = end; end = i; dragging = 2 } else start = i
        } else {
            if (i < start) { end = start; start = i; dragging = 1 } else end = i
        }
        // 拖到视口上下边缘时跟着滚
        val edge = dp(48f)
        if (vy > scroll.height - edge) scroll.scrollY = (scroll.scrollY - dp(10f)).coerceAtLeast(0f)
        else if (vy < edge) scroll.scrollY = (scroll.scrollY + dp(10f)).coerceAtMost(scroll.maxY)
    }

    fun release() { dragging = 0 }

    /** 长按选词后不松手接着拖：像拖终点拖柄一样延伸选区。 */
    fun extendFromLongPress() { if (active) { dragging = 2; lift = 0f } }

    fun act() {
        val rt = root ?: return
        if (rt.scene == null) { clear(); return }
        // 回复还在流式更新时文字会变：重新收集并夹住选区
        collect()
        if (glyphs.isEmpty()) { clear(); return }
        end = end.coerceIn(0, glyphs.lastIndex)
        start = start.coerceIn(0, end)
    }

    private fun copySelection() {
        if (!active) return
        val s = glyphs[start].char
        val e = glyphs[end].let { it.char + Character.charCount(text.codePointAt(it.char)) }
        val t = text.substring(s, e.coerceAtMost(text.length))
        clear()
        copy(t)
    }

    // ---- 字形 ----

    private fun collect() {
        glyphs.clear()
        val sb = StringBuilder()
        var line = 0
        fun add(l: Label) {
            val layout = l.glyphLayout
            if (layout.runs.isEmpty) return
            if (sb.isNotEmpty()) sb.append('\n')
            val base = sb.length
            val data = l.style.font.data
            val plain = plain(l.text, data.markupEnabled)
            sb.append(plain)
            var p = 0
            var lastY = Float.NaN
            for (run in layout.runs) {
                if (run.y != lastY) { line++; lastY = run.y }
                val baseline = l.textY + run.y - data.capHeight
                val top: Float
                val bottom: Float
                if (data.cssLineHeight > 0f) {
                    top = baseline + (data.cssLineHeight - (data.cssAscent + data.cssDescent)) / 2f + data.cssAscent
                    bottom = top - data.cssLineHeight
                } else {
                    top = l.textY + run.y + (data.lineHeight - data.capHeight) / 2f
                    bottom = top - data.lineHeight
                }
                var gx = l.textX + run.x + run.xAdvances[0]
                for (i in 0 until run.glyphs.size) {
                    val adv = run.xAdvances[i + 1]
                    // 字形和纯文本对齐：换行处的空格、换行符没有字形，往后找几个字符
                    val id = run.glyphs[i].id
                    var q = p
                    while (q < plain.length && q < p + 8 && plain.codePointAt(q) != id) q++
                    val ci = if (q < plain.length && plain.codePointAt(q) == id) q else p.coerceAtMost(plain.length - 1).coerceAtLeast(0)
                    if (plain.isNotEmpty()) glyphs += G(l, base + ci, gx, adv, top, bottom, line)
                    p = (ci + if (ci < plain.length) Character.charCount(plain.codePointAt(ci)) else 1).coerceAtMost(plain.length)
                    gx += adv
                }
            }
        }
        fun walk(e: Element) {
            if (!e.visible) return
            if (e is Label) add(e)
            else if (e is Group) e.children.forEach { walk(it) }
        }
        root?.let { walk(it) }
        text = sb.toString()
        offsets.clear()
    }

    /** Label 原点在视口里的位置（每帧缓存）。 */
    private fun origin(l: Label): Vec2 = offsets.getOrPut(l) { Vec2(l.localToAscendantCoordinates(scroll, tmp.set(0f, 0f))) }

    private fun glyphAt(vx: Float, vy: Float): Int {
        offsets.clear()
        var best = 0
        var score = Float.MAX_VALUE
        for ((i, g) in glyphs.withIndex()) {
            val o = origin(g.label)
            val top = o.y + g.top
            val bottom = o.y + g.bottom
            val dy = if (vy > top) vy - top else if (vy < bottom) bottom - vy else 0f
            val cx = o.x + g.x + g.w / 2f
            val s = dy * 1000f + kotlin.math.abs(vx - cx)
            if (s < score) { score = s; best = i }
        }
        return best
    }

    private fun kind(cp: Int): Int = when {
        Character.isLetterOrDigit(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN -> 1
        Character.isLetterOrDigit(cp) || cp == '_'.code -> 2
        else -> 0
    }

    /** 选中 [i] 所在的词：连续的汉字或连续的字母数字（同一个 Label 内）；标点只选它自己。 */
    private fun selectWord(i: Int) {
        val k = kind(text.codePointAt(glyphs[i].char))
        start = i
        end = i
        if (k == 0) return
        while (start > 0 && glyphs[start - 1].label === glyphs[i].label && kind(text.codePointAt(glyphs[start - 1].char)) == k) start--
        while (end < glyphs.lastIndex && glyphs[end + 1].label === glyphs[i].label && kind(text.codePointAt(glyphs[end + 1].char)) == k) end++
    }

    /** 起点拖柄顶在首字左下角，终点拖柄顶在末字右下角；不在视口里返回 null。 */
    private fun anchor(first: Boolean): Pair<Float, Float>? {
        if (!active || glyphs.isEmpty()) return null
        val g = glyphs[if (first) start else end]
        val o = origin(g.label)
        val ax = o.x + g.x + if (first) 0f else g.w
        val ay = o.y + g.bottom
        if (ay < 0f || o.y + g.top > scroll.height) return null
        return ax to ay
    }

    private fun drawSelection(ox: Float, oy: Float, a: Float) {
        if (!active || glyphs.isEmpty()) return
        offsets.clear()
        Draw.color(K.accent.r, K.accent.g, K.accent.b, 0.32f * a)
        var i = start
        while (i <= end) {
            val g = glyphs[i]
            var j = i
            while (j + 1 <= end && glyphs[j + 1].line == g.line && glyphs[j + 1].label === g.label) j++
            val o = origin(g.label)
            val l = o.x + g.x
            val rgt = o.x + glyphs[j].x + glyphs[j].w
            val top = (o.y + g.top).coerceAtMost(scroll.height)
            val bottom = (o.y + g.bottom).coerceAtLeast(0f)
            if (top > bottom) Fill.crect(ox + l, oy + bottom, rgt - l, top - bottom)
            i = j + 1
        }
        Draw.reset()
        val c = Color(K.accent).a(a)
        val r = r
        anchor(true)?.let { (ax, ay) -> Shapes.rect(ox + ax - 2 * r, oy + ay - 2 * r, 2 * r, 2 * r, r, 0f, r, r, c, 0f, null) }
        anchor(false)?.let { (bx, by) -> Shapes.rect(ox + bx, oy + by - 2 * r, 2 * r, 2 * r, 0f, r, r, r, c, 0f, null) }
    }

    /** 菜单放在选区上方（放不下就放到选区下方、拖柄下面），水平居中并夹在视口内；拖拖柄时先藏起来。 */
    private fun layoutMenu() {
        if (!active || dragging != 0 || glyphs.isEmpty()) { menu.visible = false; return }
        offsets.clear()
        val f = glyphs[start]
        val l = glyphs[end]
        val fo = origin(f.label)
        val lo = origin(l.label)
        val top = fo.y + f.top
        val bottom = lo.y + l.bottom
        if (top < 0f || bottom > scroll.height) { menu.visible = false; return }
        if (!menu.visible) {
            menu.visible = true
            menu.clearActions()
            menu.color.a = 0f
            menu.actions(arc.scene.actions.Actions.fadeIn(0.12f, arc.math.Interp.smooth))
        }
        menu.pack()
        val gap = dp(10f)
        val h = scroll.height
        var my = top + gap
        if (my + menu.height > h - dp(4f)) my = bottom - 2f * r - gap - menu.height
        my = my.coerceIn(dp(4f), (h - menu.height - dp(4f)).coerceAtLeast(dp(4f)))
        val cx = if (f.line == l.line && f.label === l.label) (fo.x + f.x + lo.x + l.x + l.w) / 2f else scroll.width / 2f
        val mx = (cx - menu.width / 2f).coerceIn(dp(8f), (scroll.width - menu.width - dp(8f)).coerceAtLeast(dp(8f)))
        menu.setPosition(mx, my)
    }

    private fun dst(x1: Float, y1: Float, x2: Float, y2: Float) = kotlin.math.hypot(x1 - x2, y1 - y2)

    companion object {
        /** 去掉 Arc 颜色标记：`[[` 是字面的 `[`，其余 `[...]` 是标记。 */
        fun plain(s: CharSequence, markup: Boolean): String {
            if (!markup) return s.toString()
            val str = s.toString()
            val sb = StringBuilder(str.length)
            var i = 0
            while (i < str.length) {
                val c = str[i]
                if (c == '[') {
                    if (i + 1 < str.length && str[i + 1] == '[') { sb.append('['); i += 2; continue }
                    val close = str.indexOf(']', i)
                    if (close > 0) { i = close + 1; continue }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        /** 长按选中后不让下面的滚动和点击接着处理这次触摸。 */
        fun takeTouch(listener: EventListener, owner: Element) {
            Core.scene?.cancelTouchFocusExcept(listener, owner)
        }
    }
}
