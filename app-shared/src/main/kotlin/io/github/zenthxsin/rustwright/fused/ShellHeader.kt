package io.github.zenthxsin.rustwright.fused

import arc.scene.ui.layout.Stack
import arc.scene.ui.layout.Table
import arc.scene.Element
import io.github.zenthxsin.rustwright.fused.FusedShell.Page
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.*

/** Renders header state; navigation and service actions are supplied by the shell. */
internal class ShellHeader(
    private val state: () -> State,
    private val rightAction: () -> Element?,
    private val openDrawer: () -> Unit,
    private val requestBack: () -> Unit,
    private val openTitle: () -> Unit,
    private val openUsage: () -> Unit,
) : Table() {
    data class State(
        val page: Page, val title: String, val subtitle: String, val color: arc.graphics.Color,
        val tool: Boolean, val key: String, val usage: Float,
    )

    private val left = Stack()
    private val menu = iconButton(PI.menu, 24f, stroke = 1.6f) { openDrawer() }.apply { name = "打开侧边栏" }
    private val back = iconButton(PI.back, 24f, stroke = 1.7f) { requestBack() }.apply { name = "返回" }
    private val title = K.text("", 16f, K.text, 600).apply { setEllipsis(true); setAlignment(arc.util.Align.center) }
    private val sub = K.text("", 12f, K.subtle)
    private val caret = K.svg(PI.chevronDown, 14f, K.subtle, 2f)
    private val ring = Tap(null, 20f) { openUsage() }.apply {
        name = "上下文用量"
        add(ContextRing({ state().usage }))
    }
    // 换页时顶栏右侧的按钮和上下文圆环都淡出 / 淡入，不瞬间消失
    private val backA = arc.math.Smooth(0f, 0.2f, arc.math.Interp.smooth)
    private val ringSlot = io.github.zenthxsin.rustwright.ui.kit.FadeSlot()
    private val right = io.github.zenthxsin.rustwright.ui.kit.FadeSlot()
    var rightKey = ""
    /** 中间：首页是「当前项目 ⌄」（点了去项目页），对话页是标题 + 项目 · 模式（点了出对话菜单）。 */
    private val mid: Tap = Tap(null, 12f) {
        openTitle()
    }.apply { name = "顶栏标题"; pressOverlay = false }
    private var midKey: String? = null
    /** 一级页的大标题，左对齐，跟内容同一条左边线。 */
    private val big = K.text("", 28f, K.text, 600, 32f).apply { setEllipsis(true) }

    /** 有副标题时两行，没有时标题单独垂直居中；首页标题后跟一个小箭头。 */
    private fun layoutMid(withSub: Boolean, withCaret: Boolean) {
        val key = "$withSub|$withCaret"
        if (midKey == key) return
        midKey = key
        mid.clearChildren()
        val line = Table()
        line.add(title).minWidth(0f).center()
        if (withCaret) line.add(caret).padLeft(dp(4f)).padTop(dp(2f))
        // 单行标题：中文字形重心偏低，往上提一点和两边图标对齐
        mid.add(line).growX().minWidth(0f).center().padBottom(if (withSub) 0f else dp(3f))
        if (withSub) mid.row().add(sub).padTop(dp(3f))
    }

    init {
        val bar = Table()
        left.add(menu)
        left.add(back)
        // 原型：padding 0 4 0 8，各项之间 gap 4
        bar.add(left).size(dp(48f)).padLeft(dp(8f))
        bar.add().width(dp(40f)).padLeft(dp(4f))
        layoutMid(false, false)
        bar.add(mid).growX().minWidth(0f).height(dp(48f)).padLeft(dp(4f))
        bar.add(ringSlot).size(dp(40f), dp(48f)).padLeft(dp(4f))
        bar.add(right).size(dp(48f)).padLeft(dp(4f)).padRight(dp(4f))
        top()
        add(bar).growX().height(dp(64f)).row()
        add(big).growX().minWidth(0f).left().padLeft(dp(20f)).padRight(dp(20f)).height(dp(BIG_TITLE_H - 8f)).padBottom(dp(8f))
    }

    /** 换页时标题文字淡入（0.2s），不瞬间换字。 */
    private fun fadeInText(l: arc.scene.Element) {
        l.clearActions()
        l.color.a = 0f
        l.actions(arc.scene.actions.Actions.fadeIn(0.2f, arc.math.Interp.smooth))
    }

    override fun act(delta: Float) {
        super.act(delta)
        val current = state()
        val page = current.page
        val tool = current.tool
        // 菜单 ↔ 返回 交叉淡入淡出（0.2s），不瞬间换
        backA.to(if (tool) 1f else 0f).update(delta)
        val b = backA.get()
        back.color.a = b; menu.color.a = 1f - b
        back.visible = b > 0.01f; menu.visible = b < 0.99f
        back.touchable = if (tool) arc.scene.event.Touchable.enabled else arc.scene.event.Touchable.disabled
        menu.touchable = if (tool) arc.scene.event.Touchable.disabled else arc.scene.event.Touchable.enabled
        val (t, s, color) = Triple(current.title, current.subtitle, current.color)
        if (title.text.toString() != t) { title.setText(t); fadeInText(title) }
        title.style.fontColor = color
        val bigText = when (page) {
            Page.SETTINGS -> "设置"
            Page.MORE -> "更多"
            else -> ""
        }
        if (big.text.toString() != bigText) { big.setText(bigText); fadeInText(big) }
        big.visible = bigText.isNotEmpty()
        // 首页（项目名）15px 常规；对话 16px；工具页 17px
        val font = when (page) {
            Page.HOME -> RwTheme.text(15f, 500, 18f)
            Page.CHAT -> RwTheme.text(16f, 600, 19.2f)
            else -> RwTheme.text(17f, 600, 20.4f)
        }
        if (title.style.font !== font) { title.style.font = font; title.invalidateHierarchy() }
        if (sub.text.toString() != s) sub.setText(s)
        layoutMid(s.isNotEmpty(), page == Page.HOME)
        val showRing = page == Page.CHAT
        ringSlot.show(if (showRing) ring else null)
        val key = current.key
        if (key != rightKey) {
            rightKey = key
            right.show(rightAction())
        }
    }
}
