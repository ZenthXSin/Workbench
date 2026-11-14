package io.github.zenthxsin.rustwright.ui.widgets

import arc.Core
import arc.scene.ui.Dialog
import arc.scene.ui.Label
import arc.scene.ui.ScrollPane
import arc.scene.ui.TextButton
import arc.scene.ui.TextField
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.RwButton
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import kotlinx.coroutines.CompletableDeferred

/**
 * 挂起式对话框：可以从任意协程调用，内部切到 Arc 线程显示，用户选择后恢复。
 * 返回值是按钮下标；按返回键 / 点遮罩视为取消，返回 -1。
 */
object Dialogs {

    suspend fun choose(
        title: String,
        message: String?,
        buttons: List<String>,
        highlight: Int = buttons.lastIndex,
        /** 额外内容（在 Arc 线程构造），如图片预览。 */
        content: (() -> arc.scene.Element)? = null,
        onHidden: () -> Unit = {},
        /** 强制竖排成选项列表（null = 按数量和字数自动决定）。 */
        list: Boolean? = null,
    ): Int {
        val result = CompletableDeferred<Int>()
        Core.app.post {
            val d = dialog(title)
            message?.let {
                // 长内容（如 diff 预览）放进滚动区，保证按钮始终在屏幕内
                val label = Label(it).apply { setWrap(true); setColor(RwTheme.Colors.text) }
                val pane = ScrollPane(Table().apply { add(label).width(dialogWidth()).top().left() }).apply { setScrollingDisabled(true, false); setFadeScrollBars(true) }
                d.cont.add(pane).width(dialogWidth()).maxHeight(Core.scene.height * if (content != null) 0.2f else 0.55f).row()
            }
            content?.let { d.cont.add(it()).size(dialogWidth()).row() }
            d.hidden(onHidden)
            // 选项多或文字长时竖排，避免横向溢出
            val vertical = list ?: (buttons.size > 3 || buttons.sumOf { it.length } > 14)
            val column = Table()
            buttons.forEachIndexed { i, text ->
                // 竖排是选项列表（当前项浅色高亮）；横排是操作按钮（主操作实心，其余文字按钮）
                val style = when {
                    vertical -> RwTheme.Styles.secondary
                    i == highlight -> RwTheme.Styles.primary
                    else -> RwTheme.Styles.ghost
                }
                val b = RwButton(text, style = style).apply {
                    isChecked = vertical && i == highlight
                    if (vertical) { label.setAlignment(arc.util.Align.left); label.setWrap(true) }
                    clicked { result.complete(i); d.hide() }
                }
                if (vertical) column.add(b).width(dialogWidth()).minHeight(Scl.scl(38f)).padTop(Scl.scl(5f)).row()
                else d.buttons.add(b).height(Scl.scl(36f)).minWidth(Scl.scl(80f)).padLeft(Scl.scl(6f))
            }
            if (vertical) {
                d.cont.add(ScrollPane(column).apply { setScrollingDisabled(true, false); setFadeScrollBars(true) })
                    .width(dialogWidth()).maxHeight(Core.scene.height * 0.6f).row()
            }
            d.closeOnBack { result.complete(-1) }
            d.hidden { result.complete(-1) }
            d.show()
        }
        return result.await()
    }

    suspend fun confirm(title: String, message: String, ok: String = "确定", cancel: String = "取消"): Boolean =
        choose(title, message, listOf(cancel, ok)) == 1

    /** 单行输入框；取消返回 null。 */
    suspend fun input(title: String, hint: String = "", initial: String = "", password: Boolean = false): String? {
        val result = CompletableDeferred<String?>()
        Core.app.post {
            val d = dialog(title)
            val field = TextField(initial).apply { setMessageText(hint); setPasswordMode(password); setPasswordCharacter('*') }
            d.cont.add(field).width(dialogWidth()).height(Scl.scl(42f))
            d.buttons.add(RwButton("取消", style = RwTheme.Styles.ghost).apply { clicked { result.complete(null); d.hide() } }).height(Scl.scl(36f)).minWidth(Scl.scl(80f)).padLeft(Scl.scl(6f))
            d.buttons.add(RwButton("确定", style = RwTheme.Styles.primary).apply { clicked { result.complete(field.text); d.hide() } }).height(Scl.scl(36f)).minWidth(Scl.scl(80f)).padLeft(Scl.scl(6f))
            d.closeOnBack { result.complete(null) }
            d.hidden { result.complete(null) }
            d.show()
            Core.scene.setKeyboardFocus(field)
        }
        return result.await()
    }

    /** 统一外观：标题左对齐、内容与按钮留白一致、按钮靠右。 */
    private fun dialog(text: String) = Dialog(text).apply {
        titleTable.clearChildren()
        title.setEllipsis(false)
        title.setWrap(true)
        titleTable.add(title).width(dialogWidth()).left().padTop(Scl.scl(14f)).padLeft(Scl.scl(20f)).padRight(Scl.scl(20f))
        cont.margin(Scl.scl(10f), Scl.scl(20f), Scl.scl(5f), Scl.scl(20f))
        buttons.right().margin(Scl.scl(6f), Scl.scl(14f), Scl.scl(14f), Scl.scl(14f))
    }

    private fun dialogWidth() = minOf(Scl.scl(340f), Core.scene.width * 0.78f)
}
