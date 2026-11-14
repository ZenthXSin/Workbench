package io.github.zenthxsin.rustwright.core.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一项贡献：[id] 带命名空间（如 `linux.terminal`），同一扩展点内唯一；[order] 越小越靠前。 */
interface Contribution {
    val id: String
    val order: Int get() = 0
}

/** 扩展点：由定义方模块声明（通常放在 api 包的 object 里），任何模块都能往里注册。 */
class ExtensionPoint<T : Contribution>(val id: String) {
    override fun toString() = "ExtensionPoint($id)"
}

/** 注册返回的句柄，[remove] 撤销这一项（已被同 id 的后来者覆盖时什么也不做）。 */
fun interface ExtensionHandle {
    fun remove()
}

/**
 * 贡献点注册表：去中心化注册、集中渲染。模块在 register / init / start 里往扩展点添加贡献，
 * 界面和助手只读注册表，不再写死有哪些功能。
 * - 同 id 后注册的覆盖先注册的（项目或用户扩展可以覆盖内置项）；
 * - 记录每项的来源模块，模块停止时 [removeOwner] 一并撤销；
 * - [changes] 给需要跟着刷新的界面订阅。
 */
class Extensions {
    private class Entry(val value: Contribution, val owner: String, val seq: Long)

    private val points = HashMap<ExtensionPoint<*>, LinkedHashMap<String, Entry>>()
    private val flows = HashMap<ExtensionPoint<*>, MutableStateFlow<List<Contribution>>>()
    private var seq = 0L

    /** 当前登记的来源；[ModuleHost] 在调用每个模块的生命周期方法时设置。 */
    @Volatile var owner: String = ""

    inline fun <R> withOwner(owner: String, block: () -> R): R {
        val prev = this.owner
        this.owner = owner
        try { return block() } finally { this.owner = prev }
    }

    fun <T : Contribution> add(point: ExtensionPoint<T>, contribution: T): ExtensionHandle {
        val entry: Entry
        synchronized(this) {
            val map = points.getOrPut(point) { LinkedHashMap() }
            map[contribution.id]?.let { RwLog.i("extensions", "${point.id}：${contribution.id} 被 ${owner.ifEmpty { "?" }} 覆盖（原来自 ${it.owner.ifEmpty { "?" }}）") }
            entry = Entry(contribution, owner, seq++)
            map[contribution.id] = entry
        }
        publish(point)
        return ExtensionHandle {
            val removed = synchronized(this) {
                val map = points[point]
                if (map?.get(contribution.id) === entry) { map.remove(contribution.id); true } else false
            }
            if (removed) publish(point)
        }
    }

    fun <T : Contribution> addAll(point: ExtensionPoint<T>, contributions: Iterable<T>): ExtensionHandle {
        val handles = contributions.map { add(point, it) }
        return ExtensionHandle { handles.forEach { it.remove() } }
    }

    /** 按 [Contribution.order]、再按注册先后排好的全部贡献。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Contribution> all(point: ExtensionPoint<T>): List<T> = synchronized(this) {
        points[point]?.values.orEmpty().sortedWith(compareBy({ it.value.order }, { it.seq })).map { it.value as T }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Contribution> get(point: ExtensionPoint<T>, id: String): T? = synchronized(this) { points[point]?.get(id)?.value as T? }

    /** 某项来自哪个模块（或扩展包）。 */
    fun ownerOf(point: ExtensionPoint<*>, id: String): String? = synchronized(this) { points[point]?.get(id)?.owner }

    @Suppress("UNCHECKED_CAST")
    fun <T : Contribution> changes(point: ExtensionPoint<T>): StateFlow<List<T>> =
        (synchronized(this) { flows.getOrPut(point) { MutableStateFlow(all(point)) } } as MutableStateFlow<List<T>>).asStateFlow()

    /** 撤销某个来源注册的全部贡献。 */
    fun removeOwner(owner: String) {
        val touched = synchronized(this) {
            points.filter { (_, map) -> map.values.removeAll { it.owner == owner } }.keys.toList()
        }
        touched.forEach { publish(it) }
    }

    private fun publish(point: ExtensionPoint<*>) {
        val flow = synchronized(this) { flows[point] } ?: return
        @Suppress("UNCHECKED_CAST")
        (flow as MutableStateFlow<List<Contribution>>).value = all(point as ExtensionPoint<Contribution>)
    }
}
