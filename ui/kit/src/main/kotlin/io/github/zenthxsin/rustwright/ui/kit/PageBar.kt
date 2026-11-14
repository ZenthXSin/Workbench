package io.github.zenthxsin.rustwright.ui.kit

import arc.scene.Element
import arc.scene.ui.Label
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.dp

/**
 * 工具页自带的顶栏（配合 [OwnHeader]）：返回 · 标题和一行实时状态（页面自己给）· 页面自己的操作图标。
 * 各页把原来压在页面底部的按钮挪上来，标题下面放这一页最要紧的状态（地图尺寸、问题数、当前目录…）。
 */
class PageBar(title: String, private val sub: (() -> String?)? = null) : Table(), OwnHeader {
    var onBack: () -> Unit = {}
    private val titleLabel = K.text(title, 18f, K.text, 600, 22f).apply { setEllipsis(true) }
    private val subLabel: Label = K.text("", 12f, K.subtle, lh = 14f).apply { setEllipsis(true) }
    private val titleArea = Table().left()
    private val actions = Table()

    init {
        add(iconButton(PI.back, 22f, 44f, stroke = 1.8f) { onBack() }.apply { name = "返回" }).size(dp(44f)).padLeft(dp(4f))
        titleArea.add(titleLabel).growX().minWidth(0f).left().row()
        if (sub != null) titleArea.add(subLabel).growX().minWidth(0f).left().padTop(dp(2f))
        add(titleArea).growX().minWidth(0f).padLeft(dp(4f))
        add(actions).padRight(dp(6f))
    }

    override fun bindBack(back: () -> Unit) { onBack = back }

    override fun act(delta: Float) {
        super.act(delta)
        val s = sub?.invoke().orEmpty()
        if (subLabel.text.toString() != s) subLabel.setText(s)
    }

    fun setTitle(t: String) { if (titleLabel.text.toString() != t) titleLabel.setText(t) }

    /** 右侧加一个图标按钮（[label] 用作名称，测试脚本按它点）。 */
    fun action(path: String, label: String, onClick: () -> Unit): Tap =
        iconButton(path, 21f, 42f, stroke = 1.8f, onClick = onClick).apply { name = label }.also { actions.add(it).size(dp(42f)) }

    /** 右侧加任意控件（如主按钮）。 */
    fun action(e: Element): PageBar { actions.add(e).padLeft(dp(4f)); return this }

    companion object {
        const val HEIGHT = 58f
    }
}

/** 页面根节点上加顶栏的便捷写法：返回顶栏，页面实现 [OwnHeader.bindBack] 时转给它。 */
fun Table.pageBar(bar: PageBar): PageBar {
    // 不加分隔线：顶栏和页面同底色，靠留白分开（参考 Claude App）
    add(bar).growX().height(dp(PageBar.HEIGHT)).row()
    return bar
}
