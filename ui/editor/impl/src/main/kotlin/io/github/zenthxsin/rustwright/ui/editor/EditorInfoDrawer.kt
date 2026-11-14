package io.github.zenthxsin.rustwright.ui.editor

import arc.graphics.Color
import arc.graphics.g2d.Shapes
import arc.scene.Element
import arc.scene.event.InputEvent
import arc.scene.event.InputListener
import arc.scene.event.Touchable
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import arc.scene.ui.layout.WidgetGroup
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K

/**
 * 字段抽屉：在路径行和代码之间，平时完全收起（高度 0）；打开时把代码往下推，从路径行下面往下滑出，高度跟内容走、最多占屏高 42%，
 * 超出就在里面滚动。底部一条把手：点一下或往上拖收起。只拦截露出来的那块的触摸，其余照常点代码。
 *
 * 动画按「像素高度」追目标（不是 0→1 的比例）：开合、内容换了变高变矮都是同一条缓动，中途改目标也不跳。
 * 每帧的时间步有上限：内容刚建好的那一帧往往偏长，不能让它把动画吃掉一大截。
 */
class EditorInfoDrawer(private val content: Table) : WidgetGroup() {
    private val pane = ScrollPane(content).apply { setScrollingDisabledX(true); setOverscroll(false, false) }
    private val handle = Element()
    /** 现在露出来的高度（像素）。 */
    private var shown = 0f
    /** 1 = 要开着，0 = 要收起（自测读这个）。 */
    private var target = 0f
    private var closeIn = 0f
    private var panelH = 0f
    private val handleH = dp(18f)
    private val border = Color()
    private val fill = Color()
    private val grip = Color()

    /** 用户自己点把手收起（和自动收起区分开：手动收起后这一行不再自动弹出）。 */
    var onUserClose: (() -> Unit)? = null

    /** 开 / 关变化时通知（路径行的箭头跟着翻）。 */
    var onToggle: ((Boolean) -> Unit)? = null

    /** 露出高度每变一次通知（编辑器据此让位）。 */
    var onShown: ((Float) -> Unit)? = null

    /** 高度停下来时通知（把光标所在行滚进可见区域）。 */
    var onSettled: (() -> Unit)? = null

    val isOpen: Boolean get() = target > 0f

    /** 还看得见（包括正在收起的那一小段时间）。 */
    val isShowing: Boolean get() = shown > 0.5f

    /** 高度还在变。 */
    var animating = false
        private set

    init {
        touchable = Touchable.childrenOnly
        addChild(pane)
        addChild(handle)
        handle.touchable = Touchable.enabled
        handle.name = "收起字段抽屉"
        handle.addListener(object : InputListener() {
            var y0 = 0f
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: arc.input.KeyCode): Boolean { y0 = event.stageY; return true }
            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: arc.input.KeyCode) {
                // 点一下或往上拖都收起
                if (event.stageY - y0 > -dp(4f)) { close(); onUserClose?.invoke() }
            }
        })
    }

    fun open() {
        closeIn = 0f
        if (target == 1f) return
        target = 1f
        onToggle?.invoke(true)
    }

    /**
     * 收起。[delay] 秒后才真的开始收：自动收起用它，免得「候选刚关、字段说明还没回来」的那一瞬间抽屉先缩回去又弹出来。
     * 这段时间里再 [open] 就当没关过。
     */
    fun close(delay: Float = 0f) {
        if (target == 0f) { if (delay <= 0f) closeIn = 0f; return }
        target = 0f
        closeIn = delay
        onToggle?.invoke(false)
    }

    fun toggle() { if (isOpen) close() else open() }

    override fun act(delta: Float) {
        super.act(delta)
        val dt = delta.coerceIn(0f, 1f / 30f)
        if (closeIn > 0f) closeIn -= dt
        val goal = if (target > 0f || closeIn > 0f) measure() else 0f
        val d = goal - shown
        if (d == 0f) { if (animating) { animating = false; onSettled?.invoke() }; return }
        // 指数趋近：开头快、收尾慢，约 200ms 到位；收起略快一点
        val rate = if (goal > shown) 16f else 20f
        val step = d * (1f - kotlin.math.exp(-rate * dt))
        shown = if (kotlin.math.abs(d) < 0.75f) goal else shown + step
        animating = true
        // 抽屉浮在编辑器上方、不占布局：每帧只改数字（编辑器让位 + 抽屉自己的内容裁剪），不重排整页
        onShown?.invoke(shown)
        layoutShown()
    }

    /** 内容完全展开要多高（含把手）。 */
    private fun measure(): Float {
        if (width <= 0f) return panelH
        content.width = width
        content.validate()
        val max = (scene?.height ?: dp(800f)) * 0.42f
        panelH = (content.prefHeight + handleH).coerceAtMost(max).coerceAtLeast(handleH + dp(40f))
        return panelH
    }

    override fun layout() { layoutShown() }

    /** 自己铺满编辑器区域，只有顶上 [shown] 高的一条是抽屉：内容顶对齐、从上往下露出来；把手贴在露出部分的底边。 */
    private fun layoutShown() {
        val ph = if (panelH > 0f) panelH else measure()
        pane.setBounds(0f, height - (ph - handleH), width, ph - handleH)
        handle.setBounds(0f, height - shown, width, handleH)
        pane.visible = shown > 0.5f; handle.visible = pane.visible
        pane.validate()
    }

    override fun hit(x: Float, y: Float, touchable: Boolean): Element? {
        if (shown <= 0.5f || y < height - shown || y > height || x < 0f || x > width) return null
        // 把手压在内容上面：露出高度不够时内容的下半截在把手后面，不该点得到
        if (y <= height - shown + handleH) return if (touchable && this.touchable == Touchable.disabled) null else handle
        return super.hit(x, y, touchable)
    }

    override fun draw() {
        validate()
        if (shown <= 0.5f || height < 1f) return
        val a = parentAlpha * color.a
        val by = y + height - shown
        // 面板只占顶上 shown 高：上沿直角（往上多画一个圆角再裁掉）、下边圆角 + 描边
        if (clipBegin(x, by, width, shown)) {
            Shapes.rect(x, by, width, shown + dp(14f), dp(12f), fill.set(K.surface).a(a), dp(1f), border.set(K.dl("3A3936", "CFCCC4")).a(a))
            if (shown > handleH && clipBegin(x, by + handleH, width, shown - handleH)) { drawChildren(); clipEnd() }
            Shapes.rect(x + width / 2 - dp(18f), by + dp(7f), dp(36f), dp(4f), dp(2f), grip.set(K.subtle).a(0.6f * a), 0f, Color.clear)
            clipEnd()
        }
    }

    override fun getPrefWidth() = 0f
    override fun getPrefHeight() = 0f
}
