package arc.ui.dsl

import arc.Core
import arc.scene.Scene
import arc.scene.ui.Dialog
import arc.scene.ui.layout.Cell
import arc.scene.ui.layout.Table

/**
 * Dialog 作用域 —— 集 [Dialog] 本体、内容区 [TableScope]、按钮区 [TableScope] 与 cell 配置于一体。
 *
 * [Dialog] 是独立顶层窗口（非塞进宿主的 cell），故创建时 cell 为 null；多数情况下直接 [show] 到
 * `Core.scene`。内容写在 [cont]（等价 `cont { ... }`），底部按钮写在 [buttons]（等价 `buttons { ... }`）。
 *
 * 用法：
 * ```kotlin
 * dialog("设置") {
 *     cont { label("……"); row(); button("确定") { clicked { it.dismiss() } } }
 *     shown { refresh() }
 *     closeOnBack()
 * }.show()
 * ```
 *
 * @author Eve
 */
open class DialogScope @JvmOverloads constructor(
    /** 本 scope 承载的 [Dialog]。 */
    val dialog: Dialog,
    cell: Cell<*>? = null
) : CellConfig {

    /** 本 scope 元素在父容器中的 cell；Dialog 通常独立为 null。 */
    override var cell: Cell<*>? = cell
        internal set

    /** 内容区 [TableScope]（Dialog 中部）。 */
    val cont: TableScope = TableScope(dialog.cont, null)

    /** 底部按钮区 [TableScope]。 */
    val buttons: TableScope = TableScope(dialog.buttons, null)

    // ---- 标题 ----

    /** 对话框标题文本。 */
    var title: String
        get() = dialog.title.text.toString()
        set(v) { dialog.title.setText(v) }

    // ---- Dialog 行为 ----

    /** 显示时回调。 */
    fun shown(r: Runnable) = dialog.shown(r)
    /** 隐藏时回调。 */
    fun hidden(r: Runnable) = dialog.hidden(r)
    /** 关闭时回调。 */
    fun closeOnBack() = dialog.closeOnBack()
    /** 右上角加关闭按钮。 */
    fun addCloseButton() = dialog.addCloseButton()
    /** 居中（在父容器/屏幕中心显示）。 */
    fun centerWindow() = dialog.centerWindow()
    /** 是否模态（模态时阻断外部点击，必须关闭才能继续）。 */
    fun isModal(): Boolean = dialog.isModal()
    /** 设置是否模态（阻断外部点击）。 */
    fun setModal(m: Boolean) = dialog.setModal(m)
    /** 设置是否可拖动。 */
    fun setMovable(m: Boolean) = dialog.setMovable(m)
    /** 设置是否可缩放（拖边框调整大小）。 */
    fun setResizable(r: Boolean) = dialog.setResizable(r)
    /** 设置是否居中显示。 */
    fun setCentered(c: Boolean) = dialog.setCentered(c)

    /** 显示（默认到 [Core.scene]）。 */
    fun show(stage: Scene = Core.scene): Dialog = dialog.show(stage)
    /** 隐藏。 */
    fun hide() = dialog.hide()
    /** 切换显示/隐藏。 */
    fun toggle() = dialog.toggle()

    // ---- 内容/按钮区便捷 ----

    /** 在内容区 [TableScope] 上运行 DSL。 */
    fun cont(init: TableScope.() -> Unit): TableScope = cont.apply(init)
    /** 在按钮区 [TableScope] 上运行 DSL。 */
    fun buttons(init: TableScope.() -> Unit): TableScope = buttons.apply(init)
}

/**
 * 创建并返回 [Dialog]（独立顶层窗口，不加入宿主 cell）；常见用法是紧接着 [DialogScope.show]。
 * 样式经 [DialogStyle] 懒取：因 [Dialog] 构造需要非空 style，这里取 [Core.scene] 的默认样式
 * （与 Mindustry `BaseDialog` 一致，需 scene 已在运行，即构建界面时）。
 */
fun TableScope.dialog(title: String, init: DialogScope.() -> Unit = {}): Dialog {
    val d = Dialog(title, Core.scene.getStyle(Dialog.DialogStyle::class.java))
    DialogScope(d).init()
    return d
}

/** 创建并返回一个接入 [Core.scene] 并已 [Dialog.scene] 的 [Dialog]。 */
fun TableScope.dialogShow(title: String, init: DialogScope.() -> Unit = {}): Dialog {
    val d = dialog(title, init)
    d.show()
    return d
}
