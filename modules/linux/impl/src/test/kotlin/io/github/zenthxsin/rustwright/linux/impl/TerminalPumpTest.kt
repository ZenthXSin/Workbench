package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.runtime.LifecycleDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalPumpTest {
    @Test
    fun outputSignalsCoalesceAndContinueWhileUiIsPaused() {
        val ui = ManualDispatcher()
        val background = ManualDispatcher()
        val dispatcher = LifecycleDispatcher(ui, background)
        val scope = CoroutineScope(SupervisorJob())
        var batches = 0
        val pump = TerminalPump(scope, dispatcher) { batches++ }
        repeat(1000) { pump.request() }
        assertEquals(0, batches)
        dispatcher.pause()
        background.drain()
        assertEquals(1, batches)
        repeat(1000) { pump.request() }
        background.drain()
        assertEquals(2, batches)
        dispatcher.resume()
        ui.drain()
        assertEquals(2, batches)
        pump.request()
        ui.drain()
        assertEquals(3, batches)
        pump.close()
        pump.request()
        ui.drain()
        assertEquals(3, batches)
        scope.cancel()
        ui.drain()
    }

    @Test
    fun signalDuringConsumptionIsNotLostAndYieldsBetweenBatches() {
        val ui = ManualDispatcher()
        val background = ManualDispatcher()
        val dispatcher = LifecycleDispatcher(ui, background)
        val scope = CoroutineScope(SupervisorJob())
        var batches = 0
        lateinit var pump: TerminalPump
        pump = TerminalPump(scope, dispatcher) {
            if (++batches == 1) pump.request()
        }
        dispatcher.pause()
        pump.request()
        background.runOne()
        assertEquals(1, batches)
        // 后续字节已经来了，但当前片段须允许 UI 恢复，不能一直持有后台状态线程。
        dispatcher.resume()
        background.drain()
        assertEquals(1, batches)
        ui.drain()
        assertEquals(2, batches)
        pump.close()
        scope.cancel()
        ui.drain()
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { synchronized(tasks) { tasks.addLast(block) } }
        fun runOne(): Boolean = (synchronized(tasks) { tasks.pollFirst() } ?: return false).let { it.run(); true }
        fun drain() { while (runOne()) { } }
    }
}
