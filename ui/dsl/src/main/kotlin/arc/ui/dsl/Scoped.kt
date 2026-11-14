package arc.ui.dsl

import arc.scene.ui.layout.Table

/**
 * 顶层入口：对已存在的 root [Table] 构建 DSL。
 *
 * 顶层 root 元素的 cell 为 null，故顶层块内的 pad/fillX 等 cell 方法不生效（不会崩溃）——
 * 它们只对「本 scope 元素被父容器放置」时的 cell 生效。若需在 root 上配置自身，请直接用
 * [TableScope.table]、[TableScope.self] 或底层 [Table] API。
 *
 * 用法：
 * ```
 * root.scoped {
 *     table {
 *         touchable = arc.scene.event.Touchable.enabled
 *         repeat(3) {
 *             it.table { label("x") }
 *         }
 *         row()
 *     }
 * }
 * ```
 */
fun Table.scoped(init: TableScope.() -> Unit): TableScope {
    val scope = TableScope(this)
    scope.init()
    return scope
}

/**
 * 便捷：在 [TableScope] 内重复 [times] 次执行 [init]（作用于同一个 scope）。
 * 通常配合 `table { }` 生成多行/多列项。
 */
inline fun TableScope.repeat(times: Int, init: TableScope.() -> Unit) {
    for (i in 0 until times) init()
}
