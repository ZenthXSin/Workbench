package arc.ui.dsl

import arc.graphics.Color
import arc.graphics.g2d.TextureRegion
import arc.input.KeyCode
import arc.scene.Element
import arc.scene.event.ClickListener
import arc.scene.event.Touchable
import arc.scene.style.Drawable
import arc.scene.ui.Image
import arc.scene.ui.Label
import arc.scene.ui.layout.Cell
import arc.scene.ui.layout.Table
import arc.util.Align

/** 对齐常量别名（[Align]）。 */
typealias Align = arc.util.Align

/**
 * 组合式 Table 布局 DSL —— 核心作用域。
 *
 * 设计目标：让 `table { ... }` 的内部同时是
 *  - 元素作用域：`this` 上可写元素属性/事件（touchable、clicked、background 等）；
 *  - cell 作用域：`this` 上可直接调用 pad/fillX/grow/row 等，作用于「本 scope 元素在父容器中的 cell」。
 *
 * 由此，元素操作与 cell 布局写在同一个块里，无需把链式配置拆回外层。
 *
 * 本 DSL 以 Kotlin 拓展函数组织（见同包各文件），本类是组合这些拓展的作用域载体：
 *  - [Table.scoped] 为顶层入口（顶层 root 的 cell 为 null）；
 *  - [table] 嵌套子容器的入口。
 *
 * @author Eve
 */
open class TableScope @JvmOverloads constructor(
    /** 本 scope 承载的 [Table] 元素。 */
    val table: Table,
    /** 本 scope 元素在父容器中的 cell；顶层 root 为 null。 */
    cell: Cell<*>? = null
) : CellConfig {
    /** 本 scope 元素在父容器中的 cell；顶层 root 为 null。 */
    override var cell: Cell<*>? = cell
        internal set

    // ---- 元素作用域：转发到底层 Table，可直接赋值（touchable = ...）----

    /** 显式取得底层 [Table]（用于传给需要 Table 的外部 builder）。 */
    val self: Table get() = table

    /** 触控状态。 */
    var touchable: Touchable
        get() = table.touchable
        set(v) { table.touchable = v }

    /** 背景 drawable。 */
    var background: Drawable?
        get() = table.background
        set(v) { table.background = v }

    /** 元素名。 */
    var name: String
        get() = table.name
        set(v) { table.name = v }

    /** 元素颜色。 */
    var color: Color
        get() = table.color
        set(v) { table.color.set(v) }

    /** 绑定单击。 */
    fun clicked(r: Runnable): ClickListener = table.clicked(r)
    /** 绑定指定按键的点击。 */
    fun clicked(button: KeyCode, r: Runnable): ClickListener = table.clicked(button, r)
    /** 每帧更新回调。 */
    fun update(r: Runnable) { table.update(r) }
    /** 内容对齐（[Align] 常量）：同时设置 cell 对齐与本 Table 内容对齐。 */
    override fun align(align: Int) { table.align(align); cell?.align(align) }

    // ---- 子容器构造 ----

    /** 在宿主 Table 上新建子 [Table]，并以它为 receiver 执行 [init]；返回子 [TableScope]（可用 [self] 取 [Table]，或链式 cell 配置）。 */
    fun table(init: TableScope.() -> Unit): TableScope {
        val c = table.table()
        val sub = TableScope(c.get(), c)
        sub.init()
        return sub
    }

    /** 在宿主 Table 上新建带背景的子 [Table]。 */
    fun table(background: Drawable, init: TableScope.() -> Unit): TableScope {
        val c = table.table(background)
        val sub = TableScope(c.get(), c)
        sub.init()
        return sub
    }

    /** 追加已构造好的任意元素，可配置元素属性与 cell。 */
    fun <T : Element> add(element: T, init: ElementCellScope<T>.() -> Unit = {}): T {
        val c = table.add(element)
        val s = ElementCellScope<T>(c)
        s.init()
        return s.elem
    }

    /** 追加新构造的任意元素，构建后可配置元素属性与 cell。 */
    fun <T : Element> add(block: () -> T, init: ElementCellScope<T>.() -> Unit = {}): T {
        val c = table.add(block())
        val s = ElementCellScope<T>(c)
        s.init()
        return s.elem
    }

    // ---- 便捷元素 ----

    /** 追加文本 [Label]。 */
    fun label(text: CharSequence, init: ElementCellScope<Label>.() -> Unit = {}): Label =
        add({ table.add(text).get() }, init)

    /** 追加纹理 [Image]。 */
    fun image(tex: TextureRegion?, init: ElementCellScope<Image>.() -> Unit = {}): Image =
        add({ table.image(tex).get() }, init)

    /** 追加 Drawable [Image]。 */
    fun image(d: Drawable?, init: ElementCellScope<Image>.() -> Unit = {}): Image =
        add({ table.image(d).get() }, init)

    // ---- cell / 行布局（作用于本 scope 在父中的 cell） ----

    /** 换行。 */
    fun row() { table.row() }
    /** 清空子元素。 */
    fun clear() { table.clear() }
    /** 重置为默认布局。 */
    fun reset() { table.reset() }
}
