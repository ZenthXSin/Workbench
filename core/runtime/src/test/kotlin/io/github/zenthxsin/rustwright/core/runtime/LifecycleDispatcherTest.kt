package io.github.zenthxsin.rustwright.core.runtime

import kotlinx.coroutines.CoroutineDispatcher
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifecycleDispatcherTest {
    @Test
    fun producersCanEnqueueWhileStateFragmentIsWaitingForIo() {
        val ui = ManualDispatcher()
        val background = ManualDispatcher()
        val state = LifecycleDispatcher(ui, background)
        val entered = CountDownLatch(1)
        val enqueued = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var producerReturned = false
        state.pause()
        state.enqueue {
            entered.countDown()
            producerReturned = enqueued.await(5, TimeUnit.SECONDS)
            finished.countDown()
        }
        val consumer = thread(name = "test-consumer") { background.runOne() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val producer = thread(name = "test-producer") {
            state.enqueue { }
            enqueued.countDown()
        }
        try {
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(producerReturned)
            consumer.join(5000)
            producer.join(5000)
            background.drain()
        } finally {
            enqueued.countDown()
            consumer.join(5000)
            producer.join(5000)
        }
    }

    @Test
    fun pauseDrainsExistingStateWorkWithoutRunningUiCallbacks() {
        val ui = ManualDispatcher()
        val background = ManualDispatcher()
        val state = LifecycleDispatcher(ui, background)
        val events = mutableListOf<String>()
        state.enqueue { events += "state before pause" }
        ui.enqueue { events += "ui" }
        state.pause()
        state.enqueue { events += "state after pause" }
        background.drain()
        assertEquals(listOf("state before pause", "state after pause"), events)

        state.resume()
        ui.drain()
        assertEquals(listOf("state before pause", "state after pause", "ui"), events)
    }

    @Test
    fun repeatedTransitionsKeepFifoAndDiscardStaleWakeups() {
        val ui = ManualDispatcher()
        val background = ManualDispatcher()
        val state = LifecycleDispatcher(ui, background)
        val ran = mutableListOf<Int>()
        repeat(100) { i ->
            state.enqueue { ran += i }
            state.pause()
            state.pause()
            state.resume()
            state.resume()
        }
        background.drain()
        assertTrue(ran.isEmpty())
        ui.drain()
        assertEquals((0 until 100).toList(), ran)
    }

    @Test
    fun resumeWaitsForCurrentStateFragmentBeforeUiMayRead() {
        val ui = ManualDispatcher()
        val background = ManualDispatcher()
        val state = LifecycleDispatcher(ui, background)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resuming = CountDownLatch(1)
        val resumed = CountDownLatch(1)
        var value = 0
        state.pause()
        state.enqueue {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            value = 1
            state.enqueue { value = 2 }
        }
        val worker = thread(name = "test-background") { background.runOne() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val activity = thread(name = "test-activity") {
            resuming.countDown()
            state.resume()
            resumed.countDown()
        }
        try {
            assertTrue(resuming.await(5, TimeUnit.SECONDS))
            // 等到交接线程确实被锁阻塞，不以时间猜测前后台交错。
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (activity.state != Thread.State.WAITING && resumed.count > 0 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.WAITING, activity.state)
            assertEquals(1L, resumed.count)
            release.countDown()
            assertTrue(resumed.await(5, TimeUnit.SECONDS))
            worker.join(5000)
            activity.join(5000)
            assertFalse(worker.isAlive)
            assertFalse(activity.isAlive)
            assertEquals(1, value)
            background.drain() // 恢复前投递的后台票据已失效。
            assertEquals(1, value)
            ui.drain()
            assertEquals(2, value)
        } finally {
            release.countDown()
            worker.join(5000)
            activity.join(5000)
        }
    }

    @Test
    fun reentrantDispatchDefersNewWorkAndPreservesOrder() {
        val ui = ManualDispatcher()
        val background = ManualDispatcher()
        val state = LifecycleDispatcher(ui, background)
        val events = mutableListOf<Int>()
        state.enqueue { events += 1; state.enqueue { events += 3 } }
        state.enqueue { events += 2 }
        ui.runOne()
        assertEquals(listOf(1, 2), events)
        ui.drain()
        assertEquals(listOf(1, 2, 3), events)
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { synchronized(tasks) { tasks.addLast(block) } }
        fun runOne(): Boolean = (synchronized(tasks) { tasks.pollFirst() } ?: return false).let { it.run(); true }
        fun drain() { while (runOne()) { } }
    }

    private fun CoroutineDispatcher.enqueue(block: () -> Unit) = dispatch(EmptyCoroutineContext, Runnable(block))
}
