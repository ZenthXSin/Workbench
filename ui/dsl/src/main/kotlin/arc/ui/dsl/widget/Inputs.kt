package arc.ui.dsl

import arc.Core
import arc.func.Cons
import arc.scene.Element
import arc.scene.ui.ProgressBar
import arc.scene.ui.Slider
import arc.scene.ui.TextArea
import arc.scene.ui.TextField
import arc.scene.ui.Tooltip
import arc.scene.ui.Touchpad
import arc.scene.ui.layout.Table

/**
 * 输入类 arc UI 元素的 DSL 化扩展（追加在 [TableScope] 上）。
 *
 * 设计对齐 [TableScope.add]/[label]：全部为顶层扩展函数，构造参数默认为 null
 * 时懒取默认样式（即 arc 里 `scene.getStyle(XXXStyle.class)` 的便捷构造）。
 * 各 style 在 v159.7 arc 中均为对应控件内的嵌套类（如 [Slider.SliderStyle]）。
 */

// ---- Slider ----

/** 追加 [Slider]，默认懒取 [Slider.SliderStyle]。 */
fun TableScope.slider(
    min: Float, max: Float, step: Float, vertical: Boolean = false,
    style: Slider.SliderStyle? = null,
    init: ElementCellScope<Slider>.() -> Unit = {}
): Slider = add({
    table.add(if (style != null) Slider(min, max, step, vertical, style)
              else Slider(min, max, step, vertical)).get()
}, init)

// ---- ProgressBar ----

/**
 * 追加 [ProgressBar]。
 *
 * 注意：本 arc 版本（v159.7）的 [ProgressBar] **只提供**带 [ProgressBar.ProgressBarStyle]
 * 的构造，没有无 style 的便捷构造。因此 style 为 null 时无法单靠懒取默认值完成——需
 * 显式经 `arc.Core.scene.getStyle()`，仅在当前已挂载 scene 时安全；否则请显式传入 style。
 */
fun TableScope.progressBar(
    min: Float, max: Float, step: Float, vertical: Boolean = false,
    style: ProgressBar.ProgressBarStyle? = null,
    init: ElementCellScope<ProgressBar>.() -> Unit = {}
): ProgressBar = add({
    table.add(ProgressBar(
        min, max, step, vertical,
        style ?: Core.scene.getStyle(ProgressBar.ProgressBarStyle::class.java)
    )).get()
}, init)

// ---- TextField ----

/** 追加 [TextField]，默认懒取 [TextField.TextFieldStyle]。 */
fun TableScope.textField(
    text: String = "",
    style: TextField.TextFieldStyle? = null,
    init: ElementCellScope<TextField>.() -> Unit = {}
): TextField = add({
    table.add(if (style != null) TextField(text, style) else TextField(text)).get()
}, init)

// ---- TextArea ----

/** 追加 [TextArea]，默认懒取 [TextField.TextFieldStyle]。 */
fun TableScope.textArea(
    text: String = "",
    style: TextField.TextFieldStyle? = null,
    init: ElementCellScope<TextArea>.() -> Unit = {}
): TextArea = add({
    table.add(if (style != null) TextArea(text, style) else TextArea(text)).get()
}, init)

// ---- Touchpad ----

/** 追加 [Touchpad]，默认懒取 [Touchpad.TouchpadStyle]。 */
fun TableScope.touchpad(
    deadzoneRadius: Float,
    style: Touchpad.TouchpadStyle? = null,
    init: ElementCellScope<Touchpad>.() -> Unit = {}
): Touchpad = add({
    table.add(if (style != null) Touchpad(deadzoneRadius, style)
              else Touchpad(deadzoneRadius)).get()
}, init)

// ---- Tooltip ----

/**
 * 给任意 [Element] 附加 [Tooltip]（Tooltip 是 InputListener，经 `addListener` 挂载）。
 *
 * 内容块以 [TableScope] 包裹，使 tooltip 内部同样可用元素 DSL：
 * ```
 * someElement.tooltip { label("提示文本") }
 * ```
 * 返回创建的 [Tooltip]，便于后续修改 manager 等。
 */
fun <E : Element> E.tooltip(init: TableScope.() -> Unit): Tooltip {
    val tp = Tooltip(Cons<Table> { t -> TableScope(t).init() })
    addListener(tp)
    return tp
}
