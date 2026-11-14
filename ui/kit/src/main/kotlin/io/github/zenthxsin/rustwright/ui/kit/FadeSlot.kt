package io.github.zenthxsin.rustwright.ui.kit

import arc.math.Interp
import arc.scene.Element
import arc.scene.actions.Actions
import arc.scene.event.Touchable
import arc.scene.ui.layout.WidgetGroup

/**
 * 一个位置上换控件时两头都有过渡：旧的淡出后移除，新的淡入（顶栏右侧按钮、上下文圆环等）。
 * 和 Switcher 不同，旧控件不会瞬间消失。[show] 传 null 只淡出。
 */
class FadeSlot(private val outSeconds: Float = 0.15f, private val inSeconds: Float = 0.2f) : WidgetGroup() {
    var current: Element? = null
        private set

    init {
        touchable = Touchable.childrenOnly
    }

    fun show(next: Element?) {
        if (next === current) return
        current?.let { old ->
            old.touchable = Touchable.disabled
            old.clearActions()
            old.actions(Actions.fadeOut(outSeconds, Interp.smooth), Actions.remove())
        }
        current = next
        if (next != null) {
            next.clearActions()
            if (next.parent !== this) {
                next.color.a = 0f
                addChild(next)
            }
            next.toFront()
            next.touchable = Touchable.enabled
            next.actions(Actions.fadeIn(inSeconds, Interp.smooth))
        }
        invalidate()
    }

    override fun layout() {
        for (e in children) e.setBounds(0f, 0f, width, height)
    }

    override fun getPrefWidth() = current?.let { (it as? arc.scene.ui.layout.Table)?.prefWidth } ?: 0f
    override fun getPrefHeight() = current?.let { (it as? arc.scene.ui.layout.Table)?.prefHeight } ?: 0f
}
