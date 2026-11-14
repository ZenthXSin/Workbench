package arc.ui.dsl

import arc.Core
import arc.graphics.Color
import arc.scene.ui.Button
import arc.scene.ui.ButtonGroup
import arc.scene.ui.ColorImage
import arc.scene.ui.TreeElement
import arc.scene.ui.layout.Cell
import arc.scene.ui.layout.Table

// ---- ColorImage ----

/**
 * 追加纯色 [ColorImage]（一种仅显示纯色的 Image，构造不取 style，scene 安全）。
 * ```kotlin
 * table {
 *     colorImage(Color.red) { size(32f) }
 * }
 * ```
 * @param color 纯色。
 * @param init 随后的元素属性与 cell 配置。
 * @author Eve
 */
fun TableScope.colorImage(color: Color, init: ElementCellScope<ColorImage>.() -> Unit = {}): ColorImage =
    add({ table.add(ColorImage(color)).get() }, init)

// ---- ButtonGroup（非 Element，纯互斥管理）----

/**
 * 将按钮加入 [ButtonGroup]，实现单选互斥。
 * 用法：`button("A") { group(btnGroup) }`，多个按钮共享同一 group。
 * @return 返回 group 以便链式。
 */
fun <T : Button> ElementCellScope<T>.group(group: ButtonGroup<in T>): ButtonGroup<in T> {
    group.add(elem)
    return group
}

/** 便捷：要素本身作为唯一互斥组（每按钮自成一组的场景用不到，通常共享一个 group）。 */
fun <T : Button> ElementCellScope<T>.singleGroup(): ButtonGroup<T> =
    ButtonGroup(elem)

// ---- TreeElement ----

/**
 * Tree 作用域 —— 持有 [TreeElement]，用 [node] 构建节点（每节点一个用 DSL 布局的 [Table] 行）。
 *
 * @author Eve
 */
open class TreeScope @JvmOverloads constructor(
    /** 本 scope 承载的 [TreeElement]。 */
    val tree: TreeElement,
    cell: Cell<*>? = null
) : CellConfig {

    /** 本 scope 元素在父容器中的 cell。 */
    override var cell: Cell<*>? = cell
        internal set

    /** 顶层加一个节点。 */
    fun node(init: TableScope.() -> Unit): TreeElement.TreeElementNode {
        val holder = Table()
        TableScope(holder).init()
        val n = TreeElement.TreeElementNode(holder)
        tree.add(n)
        return n
    }

    /** 全部展开。 */
    fun expandAll() = tree.expandAll()
    /** 全部收起。 */
    fun collapseAll() = tree.collapseAll()

    // ---- cell 配置（经由 CellConfig）----

    /** 触控状态转发。 */
    var touchable: arc.scene.event.Touchable
        get() = tree.touchable
        set(v) { tree.touchable = v }
}

/** 追加一棵 [TreeElement]（样式取 [Core.scene] 默认，需 scene 已在运行）。 */
fun TableScope.tree(init: TreeScope.() -> Unit = {}): TreeElement {
    val tr = TreeElement(Core.scene.getStyle(TreeElement.TreeStyle::class.java))
    TreeScope(tr).init()
    table.add(tr)
    return tr
}

/**
 * 节点扩展：为 [TreeElement.TreeElementNode] 追加子节点。
 * ```kotlin
 * tree {
 *     node {
 *         label("根")
 *     }.also {
 *         it.nodes {
 *             node { label("子1") }
 *             node { label("子2") }
 *         }
 *     }
 * }
 * ```
 * @author Eve
 */
fun TreeElement.TreeElementNode.node(init: TableScope.() -> Unit): TreeElement.TreeElementNode {
    val holder = Table()
    TableScope(holder).init()
    val n = TreeElement.TreeElementNode(holder)
    add(n)
    return n
}
