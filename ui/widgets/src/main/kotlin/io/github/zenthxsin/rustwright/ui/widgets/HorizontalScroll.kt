package io.github.zenthxsin.rustwright.ui.widgets

import arc.scene.Element
import arc.scene.ui.ScrollPane

/**
 * 只横向滚动的一行（工具栏、调色板、附加键）。首选 / 最小宽度报 0，
 * 内容再长也不会把外层布局撑宽，多出的部分靠滑动查看。
 */
class HorizontalScroll(content: Element) : ScrollPane(content, ScrollPaneStyle()) {
    // 空样式：不画滚动条（和网页里 overflow-x: auto + 隐藏滚动条一样）
    init {
        setScrollingDisabled(false, true)
        setFadeScrollBars(true)
    }

    override fun getPrefWidth(): Float = 0f
    override fun getMinWidth(): Float = 0f
}
