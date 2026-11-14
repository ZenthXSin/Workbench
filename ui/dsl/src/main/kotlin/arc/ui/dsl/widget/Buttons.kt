package arc.ui.dsl

import arc.graphics.g2d.TextureRegion
import arc.scene.style.Drawable
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.Button
import arc.scene.ui.CheckBox
import arc.scene.ui.ImageButton
import arc.scene.ui.TextButton

/**
 * 按钮类元素的 DSL 构建函数（顶层 [TableScope] 扩展）。
 *
 * 与 [TableScope.label]/[TableScope.image] 一致，均用 [TableScope.add] 的懒构造 lambda：
 * 元素在 `add` 被调用（即真正加入父 Table）时才 new，从而保证此时元素已处于场景树中，
 * 无参/无 style 构造内 `arc.Core.scene.getStyle(...)` 的懒取默认样式不会因空 scene 崩溃。
 */

/** 追加普通 [Button]，默认懒取样式。 */
fun TableScope.button(init: ElementCellScope<Button>.() -> Unit = {}): Button =
    add({ table.add(Button()).get() }, init)

/** 追加文本 [TextButton]，默认懒取样式。 */
fun TableScope.textButton(text: String, init: ElementCellScope<TextButton>.() -> Unit = {}): TextButton =
    add({ table.add(TextButton(text)).get() }, init)

/** 追加 [ImageButton]（[Drawable] 图标），默认懒取样式。 */
fun TableScope.imageButton(icon: Drawable?, init: ElementCellScope<ImageButton>.() -> Unit = {}): ImageButton =
    add({ table.add(ImageButton(icon)).get() }, init)

/** 追加 [ImageButton]（[TextureRegion] 转 [TextureRegionDrawable] 为图标），默认懒取样式。 */
fun TableScope.imageButton(region: TextureRegion?, init: ElementCellScope<ImageButton>.() -> Unit = {}): ImageButton =
    add({ table.add(ImageButton(TextureRegionDrawable(region))).get() }, init)

/** 追加复选 [CheckBox]，默认懒取样式。 */
fun TableScope.checkBox(text: String, init: ElementCellScope<CheckBox>.() -> Unit = {}): CheckBox =
    add({ table.add(CheckBox(text)).get() }, init)
