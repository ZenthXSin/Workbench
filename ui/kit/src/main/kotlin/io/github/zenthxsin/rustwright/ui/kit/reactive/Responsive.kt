package io.github.zenthxsin.rustwright.ui.kit.reactive

import arc.scene.Element
import arc.scene.ui.layout.Table
import arc.scene.ui.layout.WidgetGroup
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import kotlin.math.floor
import kotlin.math.max

/**
 * Arc 的布局自适应层（类似 CSS 的断点 / flex-wrap / auto-fill 网格），都按控件**自己拿到的宽度**算（容器查询），
 * 不看整块屏幕：同一个组件放在侧栏里和放在全屏里会自己选合适的排法。
 */
enum class WidthClass {
    /** 手机竖屏（< 600dp）。 */
    COMPACT,
    /** 大屏手机横屏 / 小平板竖屏（600–840dp）。 */
    MEDIUM,
    /** 平板横屏、桌面（≥ 840dp）。 */
    EXPANDED;

    companion object {
        fun ofDp(widthDp: Float) = when {
            widthDp < 600f -> COMPACT
            widthDp < 840f -> MEDIUM
            else -> EXPANDED
        }
        fun ofPx(widthPx: Float) = ofDp(widthPx / dp(1f))
    }
}

/**
 * 按自身宽度档位换布局：宽度跨档时才重建（同档内拖动、转屏不重建），当前档位也是一个 [Signal]，别处可以跟着变。
 */
class Responsive(private val build: (WidthClass) -> Element) : Table() {
    val widthClass = Signal<WidthClass?>(null)

    override fun layout() {
        if (width > 0f) {
            val wc = WidthClass.ofPx(width)
            if (wc != widthClass.peek()) {
                clearChildren()
                add(build(wc)).grow()
                widthClass.value = wc
            }
        }
        super.layout()
    }

    override fun getPrefWidth(): Float = cells.firstOrNull()?.get()?.prefWidth ?: 0f
    override fun getPrefHeight(): Float = cells.firstOrNull()?.get()?.prefHeight ?: 0f
}

/** 内容高度依赖宽度的容器：排完发现高度变了就通知父级重排（同 Label 换行的做法）。 */
abstract class WidthDependentGroup : WidgetGroup() {
    private var lastHeight = -1f
    private var lastWidth = -1f
    protected abstract fun heightFor(width: Float): Float
    protected abstract fun place(width: Float)

    override fun getPrefWidth(): Float = 0f
    override fun getMinWidth(): Float = 0f
    override fun getPrefHeight(): Float = if (width > 0f) heightFor(width) else heightFor(dp(360f))

    override fun layout() {
        place(width)
        if (width != lastWidth || prefHeight != lastHeight) {
            val changed = lastHeight >= 0f && prefHeight != lastHeight
            lastWidth = width; lastHeight = prefHeight
            if (changed) invalidateHierarchy()
        }
    }

    override fun childrenChanged() { super.childrenChanged(); invalidateHierarchy() }
}

/**
 * 自动换行的一行（CSS 的 `flex-wrap: wrap` + `gap`）：子控件按首选宽度从左往右排，放不下就换到下一行。
 */
class FlowRow(private val hgap: Float = dp(8f), private val vgap: Float = dp(8f)) : WidthDependentGroup() {
    fun add(e: Element): FlowRow { addChild(e); return this }

    private inline fun walk(width: Float, put: (Element, Float, Float, Float, Float) -> Unit): Float {
        var x = 0f; var y = 0f; var lineH = 0f
        for (c in children) {
            val w = minOf(c.prefWidth, width); val h = c.prefHeight
            if (x > 0f && x + w > width) { x = 0f; y += lineH + vgap; lineH = 0f }
            put(c, x, y, w, h)
            x += w + hgap; lineH = max(lineH, h)
        }
        return if (children.isEmpty) 0f else y + lineH
    }

    override fun heightFor(width: Float) = walk(width) { _, _, _, _, _ -> }
    override fun place(width: Float) {
        val total = heightFor(width)
        walk(width) { c, x, y, w, h -> c.setBounds(x, total - y - h, w, h); c.validate() }
    }
}

/**
 * 自动分列的网格（CSS 的 `grid-template-columns: repeat(auto-fill, minmax(min, 1fr))`）：
 * 按 [minCell] 算放得下几列（至少 1 列、最多 [maxColumns] 列），格子等宽，每行高度取这一行最高的那个。
 */
class AutoGrid(private val minCell: Float, private val gap: Float = dp(10f), private val maxColumns: Int = 6) : WidthDependentGroup() {
    fun add(e: Element): AutoGrid { addChild(e); return this }

    fun columns(width: Float): Int = floor((width + gap) / (minCell + gap)).toInt().coerceIn(1, maxColumns)

    private inline fun walk(width: Float, put: (Element, Float, Float, Float, Float) -> Unit): Float {
        val cols = columns(width)
        val cellW = (width - gap * (cols - 1)) / cols
        var y = 0f
        var i = 0
        val list = children
        while (i < list.size) {
            var rowH = 0f
            // 先按格宽排一次再量高：卡片里有换行文字时，高度要等宽度定了才准
            for (k in i until minOf(i + cols, list.size)) {
                val c = list[k]
                if (c.width != cellW) { c.width = cellW; c.invalidate() }
                c.validate()
                rowH = max(rowH, c.prefHeight)
            }
            for (k in i until minOf(i + cols, list.size)) put(list[k], (k - i) * (cellW + gap), y, cellW, rowH)
            y += rowH + gap
            i += cols
        }
        return if (list.isEmpty) 0f else y - gap
    }

    override fun heightFor(width: Float) = walk(width) { _, _, _, _, _ -> }
    override fun place(width: Float) {
        val total = heightFor(width)
        walk(width) { c, x, y, w, h -> c.setBounds(x, total - y - h, w, h); c.validate() }
    }
}
