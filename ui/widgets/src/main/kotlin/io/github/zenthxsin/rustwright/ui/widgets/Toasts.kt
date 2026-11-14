package io.github.zenthxsin.rustwright.ui.widgets

import arc.Core
import arc.scene.actions.Actions
import arc.scene.ui.Label
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.util.Align
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme

/** 短暂提示。外壳装了顶部弹窗（[handler]）时都走顶部弹窗；没有时退回屏幕底部的胶囊。 */
object Toasts {
    /** 外壳的顶部弹窗（融合版界面的通知横幅）。 */
    @JvmStatic var handler: ((String) -> Unit)? = null

    fun show(text: String, seconds: Float = 2.2f) {
        handler?.let { it(text); return }
        val scene = Core.scene ?: return
        val label = Label(text).apply { setWrap(true); setAlignment(Align.center) }
        // 短文字按内容收窄成胶囊，长文字换行
        val maxW = minOf(Scl.scl(320f), scene.width * 0.8f)
        val t = Table(RwTheme.round(20, RwTheme.Colors.surface3)).apply {
            margin(Scl.scl(8f), Scl.scl(12f), Scl.scl(8f), Scl.scl(12f))
            add(label).width(minOf(maxW, label.prefWidth + 1f))
            pack()
            touchable = arc.scene.event.Touchable.disabled
        }
        t.setPosition((scene.width - t.width) / 2f, scene.marginBottom + Scl.scl(84f))
        t.color.a = 0f
        t.actions(Actions.fadeIn(0.15f), Actions.delay(seconds), Actions.fadeOut(0.3f), Actions.remove())
        scene.add(t)
    }
}
