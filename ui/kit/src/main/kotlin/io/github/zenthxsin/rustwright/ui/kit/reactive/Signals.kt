package io.github.zenthxsin.rustwright.ui.kit.reactive

import arc.Core
import arc.scene.Element
import arc.scene.ui.Label
import arc.scene.ui.layout.Table
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Arc 的数据驱动层（类似 Compose 的状态 / Solid 的 signal）。
 *
 * 以前页面在 `act()` 里每帧把所有状态拼成签名再比对，没变也要算；现在：
 * - [Signal]：一个值。在 [effect] / [derived] 里读它会自动登记依赖。
 * - [derived]：由别的信号算出来的值，依赖变了才重算（懒算，读的时候算）。
 * - [effect]：依赖变了才重跑；同一帧里改多次只跑一次（合并到下一帧开头）。
 * - 绑到 Arc 控件上（[bindText]、[bindVisible]、[bindContent]…）：控件离开画面后自动停止更新。
 *
 * 只在界面线程读写；后台线程改值用 [Signal.post]。
 */
object Reactive {
    /** 把要重跑的副作用排到下一帧；测试里换成立即执行或手动 [flush]。 */
    @JvmStatic var poster: (Runnable) -> Unit = { r -> Core.app?.post(r) ?: r.run() }

    internal var current: Observer? = null
    private val pending = LinkedHashSet<Observer>()
    private var scheduled = false
    private var batchDepth = 0

    internal fun schedule(o: Observer) {
        pending += o
        if (batchDepth > 0 || scheduled) return
        scheduled = true
        poster(Runnable { flush() })
    }

    /** 立即跑完所有排队的副作用（跑的过程中又被触发的也会跑）。 */
    fun flush() {
        scheduled = false
        var guard = 0
        while (pending.isNotEmpty()) {
            check(++guard < 10_000) { "副作用互相触发，停不下来" }
            val next = pending.first(); pending.remove(next)
            next.rerun()
        }
    }

    /** 一次改多个信号：改完才排队，避免中间状态触发副作用。 */
    fun <T> batch(block: () -> T): T {
        batchDepth++
        try { return block() } finally {
            batchDepth--
            if (batchDepth == 0 && pending.isNotEmpty() && !scheduled) { scheduled = true; poster(Runnable { flush() }) }
        }
    }

    /** 读信号但不登记依赖。 */
    fun <T> untracked(block: () -> T): T {
        val prev = current; current = null
        try { return block() } finally { current = prev }
    }
}

/** 能被读、读的时候会登记依赖的值。 */
interface Readable<out T> { val value: T; fun peek(): T }

internal abstract class Observer {
    internal val sources = HashSet<Source>()
    abstract fun invalidate()
    abstract fun rerun()
    fun clearSources() { sources.forEach { it.observers -= this }; sources.clear() }
}

internal abstract class Source {
    internal val observers = LinkedHashSet<Observer>()
    fun track() { Reactive.current?.let { o -> observers += o; o.sources += this } }
    fun notifyObservers() { observers.toList().forEach { it.invalidate() } }
}

/** 一个可写的值。值相等（[same]）时不通知。 */
class Signal<T>(initial: T, private val same: (T, T) -> Boolean = { a, b -> a == b }) : Readable<T> {
    private val source = object : Source() {}
    private var v = initial
    override var value: T
        get() { source.track(); return v }
        set(n) { if (same(v, n)) return; v = n; source.notifyObservers() }
    override fun peek(): T = v
    /** 从后台线程改：排到界面线程再改。 */
    fun post(n: T) = Reactive.poster(Runnable { value = n })
    /** 原地改了可变对象（列表、集合）后手动通知。 */
    fun touch() { source.notifyObservers() }
    internal val asSource: Source get() = source
}

/** 派生值：懒算，依赖变了标脏并通知下游，下次读时才重算（下游的 [bindText] 等会再比一次值，值没变就不动控件）。 */
class Derived<T>(private val compute: () -> T) : Readable<T> {
    private val source = object : Source() {}
    private var dirty = true
    private var cached: T? = null
    private val self = object : Observer() {
        override fun invalidate() { if (!dirty) { dirty = true; source.notifyObservers() } }
        override fun rerun() {}
    }
    private fun refresh() {
        if (!dirty) return
        self.clearSources()
        val prev = Reactive.current; Reactive.current = self
        try { cached = compute() } finally { Reactive.current = prev }
        dirty = false
    }
    @Suppress("UNCHECKED_CAST")
    override val value: T get() { refresh(); source.track(); return cached as T }
    @Suppress("UNCHECKED_CAST")
    override fun peek(): T { refresh(); return cached as T }
}

fun <T> signal(initial: T) = Signal(initial)
fun <T> derived(compute: () -> T) = Derived(compute)

/** 停止一个副作用。 */
fun interface Disposable { fun dispose() }

/**
 * 副作用：立刻跑一次，之后依赖变了在下一帧重跑。
 *
 * 给了 [owner]（控件）时：控件不在画面上（切到别的页面、还没挂上去）就先暂停——退订所有依赖、
 * 在控件上挂一个只跑一次的 Action；Arc 只给画面上的控件跑 Action，所以控件回到画面的第一帧会补跑一次并重新订阅。
 * 永远不回来的控件不再被任何信号引用，跟着一起被回收，不用手动释放。
 */
fun effect(owner: Element? = null, block: () -> Unit): Disposable {
    val o = object : Observer() {
        var disposed = false
        var queued = false
        var waiting = false
        override fun invalidate() { if (!disposed && !queued) { queued = true; Reactive.schedule(this) } }
        fun run() {
            clearSources()
            val prev = Reactive.current; Reactive.current = this
            try { block() } finally { Reactive.current = prev }
        }
        override fun rerun() {
            queued = false
            if (disposed) return
            if (owner != null && owner.scene == null) {
                clearSources()
                if (!waiting) { waiting = true; owner.addAction(arc.scene.actions.Actions.run { waiting = false; if (!disposed) run() }) }
                return
            }
            run()
        }
    }
    o.run()
    return Disposable { o.disposed = true; o.clearSources() }
}

/** `var x by signal(0)`：普通属性的写法，读写自动登记依赖 / 通知。 */
operator fun <T> Signal<T>.getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = value
operator fun <T> Signal<T>.setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, v: T) { value = v }

/** 依赖变了才重写文字（文字相同不 setText，免得 Label 重新排版）。 */
fun Label.bindText(text: () -> CharSequence): Label {
    effect(this) { val t = text(); if (this.text.toString() != t.toString()) setText(t) }
    return this
}

fun <E : Element> E.bindVisible(visible: () -> Boolean): E { effect(this) { val v = visible(); if (this.visible != v) { this.visible = v; parent?.invalidateHierarchy() } }; return this }

fun <E : Element> E.bindAlpha(alpha: () -> Float): E { effect(this) { color.a = alpha() }; return this }

/** 依赖变了就清空重建这张表的内容（列表、标签栏这类整块随数据变的区域）。 */
fun <T : Table> T.bindContent(build: T.() -> Unit): T {
    effect(this) { clearChildren(); build(); invalidateHierarchy() }
    return this
}

/** 把协程里的 StateFlow 接成信号（值在界面线程更新）。 */
fun <T> StateFlow<T>.toSignal(scope: CoroutineScope): Signal<T> {
    val s = Signal(value)
    scope.launch { collect { s.post(it) } }
    return s
}
