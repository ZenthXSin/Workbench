package io.github.zenthxsin.rustwright.core.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * 全局事件总线（参考 Mindustry 的 Events，但基于 Flow，不用全局静态）。
 * 模块之间只通过事件和 api 接口通信，不直接引用彼此的实现。
 */
class Events(extraBufferCapacity: Int = 256) {
    private val flow = MutableSharedFlow<Any>(extraBufferCapacity = extraBufferCapacity)

    /** 非挂起发送；缓冲满时返回 false。 */
    fun post(event: Any): Boolean = flow.tryEmit(event)

    suspend fun emit(event: Any) = flow.emit(event)

    fun <T : Any> stream(type: KClass<T>): Flow<T> = flow.filterIsInstance(type)

    fun <T : Any> on(type: KClass<T>, scope: CoroutineScope, handler: suspend (T) -> Unit): Job =
        scope.launch { stream(type).collect { handler(it) } }

    inline fun <reified T : Any> stream(): Flow<T> = stream(T::class)
    inline fun <reified T : Any> on(scope: CoroutineScope, noinline handler: suspend (T) -> Unit): Job = on(T::class, scope, handler)
}
