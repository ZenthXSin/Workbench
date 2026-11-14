package arc.ui.dsl

import arc.scene.Element
import arc.scene.Scene
import arc.scene.event.Touchable
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Cell
import arc.scene.ui.layout.Collapser
import arc.scene.ui.layout.Spacer
import arc.scene.ui.layout.Stack
import arc.scene.ui.layout.Table

/**
 * ScrollPane 容器作用域 —— 把「Pane 本身 + 其 cell 配置 + 内容 DSL」合一，复刻 [TableScope] 的组合思路。
 *
 * 用法：
 * ```
 * table {
 *     pane {
 *         grow()        // cell 配置
 *         touchable = arc.scene.event.Touchable.enabled
 *         content {     // 内容 DSL
 *             repeat(20) { label("行 $it"); row() }
 *         }
 *     }
 * }
 * ```
 *
 * 通过 [TableScope.pane] 创建。内容容器是 [content]（一个 [TableScope]），可跑 `table{}`/`label{}`/`add{}`。
 *
 * @author Eve
 */
open class PaneScope @JvmOverloads constructor(
    /** 本 scope 承载的 [ScrollPane] 元素。 */
    val pane: ScrollPane,
    cell: Cell<*>? = null
) : CellConfig {
    /** 本 scope 元素在父容器中的 cell；顶层 root 为 null。 */
    override var cell: Cell<*>? = cell
        internal set

    /** Pane 内的内容容器（[TableScope]，可跑 `table{}`/`label{}`/`add{}` DSL）。 */
    val content = TableScope(Table(), null)

    // ---- 便捷：在内容容器上跑 DSL（等价于 `content { ... }`） ----

    /** 在 Pane 内容容器上运行 DSL，往里面放子元素。 */
    fun content(init: TableScope.() -> Unit): TableScope = content.apply(init)

    // ---- 元素作用域：转发到底层 Pane ----

    /** 触控状态。 */
    var touchable: Touchable
        get() = pane.touchable
        set(v) { pane.touchable = v }

    /** 元素名。 */
    var name: String
        get() = pane.name
        set(v) { pane.name = v }

    // ---- cell 配置（经由 CellConfig：pad/fillX/grow/width/top/colspan 等） ----
    // 本类不再各自复制 pad/fill/grow 等方法，统一由 [CellConfig] 提供。
}

/**
 * 在宿主 Table 上新建带滚动条的 [ScrollPane]，内容与 cell 在 [PaneScope] 中配置。
 *
 * 先跑 [init] 填好 [PaneScope.content]，再在调用时（已处于 scene 内）懒取默认 [ScrollPaneStyle]
 * 构造 [ScrollPane] 并作为普通 cell 加入宿主 —— 与 arc `Table.pane` 取默认 style 的时机一致，scene 安全。
 * @return 已加入宿主的 [PaneScope]（可继续在块外配置）。
 */
fun TableScope.pane(init: PaneScope.() -> Unit = {}): PaneScope {
    val scope = PaneScope(ScrollPane(Table(), arc.Core.scene.getStyle(ScrollPane.ScrollPaneStyle::class.java)))
    scope.init()
    scope.pane.widget = scope.content.table
    val c = table.add(scope.pane)
    scope.cell = c
    return scope
}

/**
 * 带自定义样式的 [TableScope.pane]。
 * @param style 求取 [ScrollPane.ScrollPaneStyle] 的块（在 scene 可用后调用）。
 */
fun TableScope.pane(style: Scene.() -> ScrollPane.ScrollPaneStyle, init: PaneScope.() -> Unit = {}): PaneScope {
    val scope = PaneScope(ScrollPane(Table(), arc.Core.scene.style()))
    scope.init()
    scope.pane.widget = scope.content.table
    val c = table.add(scope.pane)
    scope.cell = c
    return scope
}

/**
 * 在宿主 Table 上新建 [Stack]，把 DSL 构建的子 [Table] 作为唯一内容放入，并以普通 cell 加入宿主。
 *
 * Stack 会把子元素按自身尺寸堆叠（[Stack] 是 WidgetGroup），这里用 DSL 的 [Table] 作为承载，便于放多个叠层：
 * ```
 * table {
 *     stackContent {
 *         image(tex1)
 *         image(tex2).set(...)   // 叠在上层
 *     } { grow() }
 * }
 * ```
 * @param content 构建 Stack 内层 Table 的 DSL。
 * @param init 随后对 Stack 的 cell 与元素属性进行配置。
 * @return 已加入宿主的 [Stack]。
 */
fun TableScope.stackContent(content: TableScope.() -> Unit, init: ElementCellScope<Stack>.() -> Unit = {}): Stack {
    val holder = Table()
    TableScope(holder).content()
    return add({ Stack(holder) }, init)
}

/**
 * 在宿主 Table 上新建 [Stack]，把给定元素直接叠放，并以普通 cell 加入宿主。
 * @param els 依次加入 Stack 的元素（按加入顺序堆叠，后者在上）。
 * @param init 随后对 Stack 的 cell 与元素属性进行配置。
 * @return 已加入宿主的 [Stack]。
 */
fun TableScope.stack(vararg els: Element, init: ElementCellScope<Stack>.() -> Unit = {}): Stack =
    add({ Stack(*els) }, init)

/**
 * 在宿主 Table 上新建水平 [Spacer]（占位空白）。
 * @param width 期望宽度（像素）。
 */
fun TableScope.spacerX(width: Float, init: ElementCellScope<Spacer>.() -> Unit = {}): Spacer =
    add({ Spacer({ width }, { 0f }) }, init)

/**
 * 在宿主 Table 上新建垂直 [Spacer]（占位空白）。
 * @param height 期望高度（像素）。
 */
fun TableScope.spacerY(height: Float, init: ElementCellScope<Spacer>.() -> Unit = {}): Spacer =
    add({ Spacer({ 0f }, { height }) }, init)

/**
 * 在宿主 Table 上新建 [Spacer]（占位空白）。
 * @param width 期望宽度；[height] 期望高度（像素）。
 */
fun TableScope.spacer(width: Float, height: Float, init: ElementCellScope<Spacer>.() -> Unit = {}): Spacer =
    add({ Spacer({ width }, { height }) }, init)

/**
 * 在宿主 Table 上新建 [Collapser]，把 DSL 构建的子 [Table] 作为可折叠内容。
 *
 * Collapser 是收起/展开动画容器，内容为单个 Table：
 * ```
 * table {
 *     collapser({ label("头部"); row(); label("折叠内容") }, collapsed = true) { growX() }
 * }
 * ```
 * @param content 构建可折叠内容 Table 的 DSL。
 * @param collapsed 初始是否折叠。
 * @param init 随后对 Collapser 的 cell 与元素属性进行配置。
 * @return 已加入宿主的 [Collapser]。
 */
fun TableScope.collapser(
    content: TableScope.() -> Unit,
    collapsed: Boolean = false,
    init: ElementCellScope<Collapser>.() -> Unit = {}
): Collapser {
    val holder = Table()
    TableScope(holder).content()
    return add({ Collapser(holder, collapsed) }, init)
}

/**
 * 在宿主 Table 上新建空的 [Collapser]（内容之后通过 [Collapser.setTable] 填充）。
 * @param collapsed 初始是否折叠。
 */
fun TableScope.collapser(collapsed: Boolean = false, init: ElementCellScope<Collapser>.() -> Unit = {}): Collapser =
    add({ Collapser(Table(), collapsed) }, init)
