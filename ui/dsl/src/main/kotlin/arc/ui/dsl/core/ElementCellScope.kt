package arc.ui.dsl

import arc.scene.Element
import arc.scene.event.ClickListener
import arc.scene.ui.layout.Cell

/**
 * 任意元素的 cell 作用域。
 *
 * 用于 `add { ... } { ... }` / `label { ... } { ... }` 这类「先建元素、再同时配置[元素]属性与 cell]」的写法：
 *  - [elem] 元素本体（读/写元素属性、绑事件）；
 *  - `this` 上的 pad/fillX/grow 等方法作用于 [cell]。
 *
 * @author Eve
 */
open class ElementCellScope<T : Element>(val c: Cell<T>) : CellConfig {

    /** 元素本体。 */
    val elem: T = c.get()

    /** 元素在父容器中的 cell。 */
    override var cell: Cell<*>? = c
        internal set

    /** 触控状态（元素）。 */
    var touchable: arc.scene.event.Touchable
        get() = elem.touchable
        set(v) { elem.touchable = v }

    /** 绑定单击（元素）。 */
    fun clicked(r: Runnable): ClickListener = elem.clicked(r)
    /** 每帧更新回调（元素）。 */
    fun update(r: Runnable) { elem.update(r) }
}
