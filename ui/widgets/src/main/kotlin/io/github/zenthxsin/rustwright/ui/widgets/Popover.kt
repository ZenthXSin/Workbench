package io.github.zenthxsin.rustwright.ui.widgets

import arc.Core
import arc.math.geom.Vec2
import arc.scene.Element
import arc.scene.event.Touchable
import arc.scene.ui.Label
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.Lucide
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.designsystem.BackOverlay

/**
 * 锚定在某个控件上的弹出卡片（对应网页的 Popover / Select 弹层）：圆角 16、细描边，
 * 默认出现在锚点上方，点外面关闭。
 */
object Popover {

    private val open = ArrayList<() -> Unit>()

    /** 关闭所有弹层（返回键、切页时）。 */
    fun dismissAll() { open.toList().forEach { it() } }

    class Option(val label: String, val description: String? = null, val selected: Boolean = false)

    /** 弹出 [content]；返回关闭函数。 */
    fun show(
        anchor: Element,
        content: Table,
        above: Boolean = true,
        alignEnd: Boolean = false,
        width: Float? = null,
        onHide: () -> Unit = {},
    ): () -> Unit {
        val scene = Core.scene
        lateinit var hide: () -> Unit
        val panel = object : Table(RwTheme.card(RwTheme.Colors.popover, RwTheme.Colors.outline, 14)), BackOverlay {
            override fun dismissForBack(): Boolean { hide(); return true }
        }.apply {
            touchable = Touchable.enabled
            add(content).grow()
        }
        val catcher = Table().apply {
            setFillParent(true)
            touchable = Touchable.enabled
            clicked { hide() }
        }
        hide = { open.remove(hide); catcher.remove(); panel.remove(); onHide() }
        open += hide
        val margin = dp(12f)
        val w = (width ?: panel.prefWidth).coerceAtMost(scene.width - margin * 2)
        // 换行文字的高度取决于宽度：先定宽排一次再取高
        panel.setSize(w, panel.prefHeight)
        panel.validate()
        panel.height = panel.prefHeight
        panel.validate()
        // 弹层挂在根节点里；键盘高度是 Scene.marginBottom（根节点被抬高），要用相对根节点的坐标
        val root = scene.root
        val p = anchor.localToAscendantCoordinates(root, Vec2(0f, 0f))
        val x = (if (alignEnd) p.x + anchor.width - w else p.x).coerceIn(margin, root.width - margin - w)
        val gap = dp(10f)
        val y = if (above) p.y + anchor.height + gap else p.y - gap - panel.height
        panel.setPosition(x, y.coerceIn(margin, root.height - margin - panel.height))
        scene.add(catcher)
        scene.add(panel)
        return hide
    }

    /** 选项菜单（网页 CodexSelect 的弹层）：每项一行标题 + 可选说明，已选项右侧打勾。 */
    fun menu(anchor: Element, options: List<Option>, above: Boolean = true, alignEnd: Boolean = false, onPick: (Int) -> Unit) {
        var close: () -> Unit = {}
        val listeners = ArrayList<arc.scene.event.ClickListener>()
        fun anyPressed() = listeners.any { it.isPressed }
        val list = Table().top().left().apply {
            margin(dp(6f))
            options.forEachIndexed { i, o ->
                // .codex-select-option：min-h 44、圆角 8、padding 10 12；标题 14/20，说明 12/20 #737373；已选打勾 16，高亮底 #ededee
                add(Table().left().apply {
                    name = "option:" + o.label
                    touchable = Touchable.enabled
                    margin(dp(10f), dp(12f), dp(10f), dp(12f))
                    add(Table().left().apply {
                        add(Label(o.label, Label.LabelStyle(RwTheme.text(14f, 400, 20f), RwTheme.Colors.text)).apply { setWrap(true) }).growX().left().row()
                        o.description?.let {
                            add(Label(it, Label.LabelStyle(RwTheme.text(12f, 400, 20f), RwTheme.Colors.subtle)).apply { setWrap(true) }).growX().left().padTop(dp(2f))
                        }
                    }).growX().minWidth(0f)
                    if (o.selected) add(Label(Lucide.check.toString(), Label.LabelStyle(RwTheme.lucide(16f), RwTheme.Colors.text))).padLeft(dp(12f))
                    val highlight = RwTheme.round(8, RwTheme.Colors.surface2)
                    val click = clicked { close(); onPick(i) }.also { listeners += it }
                    update { background(if (click.isPressed || (o.selected && !anyPressed())) highlight else null) }
                }).growX().minHeight(dp(44f)).row()
            }
        }
        val w = minOf(Core.scene.width - dp(24f), maxOf(dp(180f), anchor.width, dp(260f)))
        close = show(anchor, list, above, alignEnd, width = w)
    }
}
