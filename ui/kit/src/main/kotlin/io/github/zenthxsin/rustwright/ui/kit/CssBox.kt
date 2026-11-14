package io.github.zenthxsin.rustwright.ui.kit

import arc.graphics.g2d.Draw
import arc.math.Interp
import arc.math.Smooth
import arc.scene.Element
import arc.scene.ui.layout.WidgetGroup
import arc.util.Align

/**
 * 复刻原型里「overflow: hidden + max-width / max-height + margin + opacity + transform: scale」各自带过渡的弹性项：
 * 显示时最大尺寸为 [maxShown]（像素），隐藏时为 0；可见尺寸 = min(内容尺寸, 当前最大尺寸)，
 * 所以内容比 [maxShown] 小时，收起动画前段看不出变化——和浏览器一样。
 * 各属性的时长、曲线、延迟分开设置（对应 CSS transition 列表）。
 */
class CssBox(
    private val content: Element,
    private val horizontal: Boolean,
    private val maxShown: Float,
    sizeDuration: Float,
    sizeInterp: Interp = Interp.standard,
    alphaDuration: Float = sizeDuration,
    alphaInterp: Interp = Interp.ease,
    alphaDelay: Float = 0f,
    /** 隐藏时的缩放（CSS scale），1 为不缩放。 */
    private val hiddenScale: Float = 1f,
    scaleDuration: Float = sizeDuration,
    scaleInterp: Interp = Interp.standard,
    /** 缩放原点（Align），如左中 = Align.left。 */
    private val origin: Int = Align.center,
    /** 显示时前 / 后外边距（像素），随尺寸一起过渡。 */
    private val marginBefore: Float = 0f,
    private val marginAfter: Float = 0f,
    /** false：没有 opacity 过渡（只裁剪尺寸）。 */
    private val fade: Boolean = true,
) : WidgetGroup() {
    private val extent = Smooth(maxShown, sizeDuration, sizeInterp)
    private val margin = Smooth(1f, sizeDuration, sizeInterp)
    private val alpha = Smooth(1f, alphaDuration, alphaInterp).delay(alphaDelay)
    private val scale = Smooth(1f, scaleDuration, scaleInterp)
    var shown = true
        private set

    init {
        addChild(content)
    }

    fun setShown(show: Boolean, animate: Boolean = true) {
        shown = show
        val e = if (show) maxShown else 0f
        val m = if (show) 1f else 0f
        val s = if (show) 1f else hiddenScale
        if (animate) {
            extent.to(e); margin.to(m); alpha.to(m); scale.to(s)
        } else {
            extent.snap(e); margin.snap(m); alpha.snap(m); scale.snap(s)
        }
        invalidateHierarchy()
    }

    private fun contentMain() = if (horizontal) content.prefWidth else content.prefHeight
    private fun visibleMain() = minOf(contentMain(), extent.get())
    private fun marginTotal() = (marginBefore + marginAfter) * margin.get()

    override fun getPrefWidth() = if (horizontal) visibleMain() + marginTotal() else content.prefWidth
    override fun getPrefHeight() = if (horizontal) content.prefHeight else visibleMain() + marginTotal()
    // 横向：可被挤窄到内容的最小宽度（内容自己省略），像 flex 子项的 min-width: auto
    override fun getMinWidth() = if (horizontal) minOf(content.minWidth, visibleMain()) + marginTotal() else content.minWidth
    override fun getMinHeight() = if (horizontal) content.minHeight else prefHeight

    override fun act(delta: Float) {
        super.act(delta)
        val before = extent.get() + margin.get()
        extent.update(delta); margin.update(delta); alpha.update(delta); scale.update(delta)
        if (extent.get() + margin.get() != before) invalidateHierarchy()
    }

    override fun layout() {
        val mb = marginBefore * margin.get()
        // 被父布局挤窄时内容跟着变窄；收起动画中（宽度 = 可见宽度）内容保持原宽、靠裁切
        if (horizontal) content.setBounds(mb, 0f, if (width - marginTotal() < visibleMain() - 0.5f) maxOf(0f, width - marginTotal()) else content.prefWidth, height)
        else content.setBounds(0f, height - mb - content.prefHeight, width, content.prefHeight)
        content.validate()
    }

    override fun draw() {
        val a = if (fade) alpha.get() else 1f
        if (a <= 0.001f || visibleMain() <= 0.01f) return
        validate()
        val mb = marginBefore * margin.get()
        val cx = if (horizontal) x + mb else x
        val cw = if (horizontal) minOf(visibleMain(), width - marginTotal()) else width
        val ch = if (horizontal) height else visibleMain()
        val cy = if (horizontal) y else y + height - mb - ch
        Draw.flush()
        if (!clipBegin(cx, cy, cw, ch)) return
        val old = color.a
        color.a = old * a
        val sc = scale.get()
        if (sc < 0.9999f) {
            isTransform = true
            val ox = when {
                origin and Align.left != 0 -> mb
                origin and Align.right != 0 -> mb + content.width
                else -> mb + content.width / 2f
            }
            setOrigin(ox, height / 2f)
            setScale(sc)
        } else {
            setScale(1f)
            isTransform = false
        }
        super.draw()
        color.a = old
        Draw.flush()
        clipEnd()
    }
}

/** 原型 rwin：从下方 [rise] 处淡入上移（CSS animation，挂载时播放一次）。 */
fun Element.rwin(duration: Float = 0.25f, rise: Float = io.github.zenthxsin.rustwright.ui.designsystem.dp(6f), interp: Interp = Interp.ease): Element {
    val e = this
    e.color.a = 0f
    e.translation.set(0f, -rise)
    e.addAction(object : arc.scene.Action() {
        var t = 0f
        override fun act(delta: Float): Boolean {
            t = (t + delta).coerceAtMost(duration)
            val p = interp.apply(t / duration)
            e.color.a = p
            e.translation.set(0f, -rise * (1f - p))
            return t >= duration
        }
    })
    return e
}


/** 原型 rwfade：从下方 [rise] 处上浮、由 0.985 放大并淡入（进入主界面 0.45s cubic-bezier(.2,.8,.2,1)）。 */
fun arc.scene.Group.rwfade(duration: Float = 0.45f, rise: Float = io.github.zenthxsin.rustwright.ui.designsystem.dp(10f), fromScale: Float = 0.985f, interp: Interp = Interp.standard) {
    val g = this
    g.color.a = 0f
    g.translation.set(0f, -rise)
    g.isTransform = true
    g.setOrigin(Align.center)
    g.setScale(fromScale)
    g.addAction(object : arc.scene.Action() {
        var t = 0f
        override fun act(delta: Float): Boolean {
            t = (t + delta).coerceAtMost(duration)
            val p = interp.apply(t / duration)
            g.color.a = p
            g.translation.set(0f, -rise * (1f - p))
            g.setScale(fromScale + (1f - fromScale) * p)
            if (t >= duration) {
                g.setScale(1f)
                g.isTransform = false
            }
            return t >= duration
        }
    })
}
