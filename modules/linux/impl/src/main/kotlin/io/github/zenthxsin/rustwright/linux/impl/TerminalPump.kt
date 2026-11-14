package io.github.zenthxsin.rustwright.linux.impl

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean

/** 读线程只发送合并信号；消费始终在生命周期协调的状态线程上，空闲时不轮询。 */
internal class TerminalPump(scope: CoroutineScope, dispatcher: CoroutineDispatcher, consume: () -> Unit) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val pending = AtomicBoolean()
    private val job = scope.launch(dispatcher) {
        for (ignored in requests) {
            pending.set(false)
            consume()
            // 连续大输出也要在批次之间让出状态线程，允许 Codex 事件和前后台交接。
            yield()
        }
    }

    fun request() { if (pending.compareAndSet(false, true)) requests.trySend(Unit) }

    fun close() {
        requests.close()
        job.cancel()
    }
}
