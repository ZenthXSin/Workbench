package io.github.zenthxsin.rustwright.core.runtime

import kotlinx.coroutines.CoroutineDispatcher
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 为与 UI 共享可变状态的任务保留单一执行者。前台任务随 UI 执行，UI 完全暂停后才交给后台。
 *
 * 平台必须在前台线程已停止读写状态后调用 [pause]，并在恢复前台线程之前调用 [resume]。
 * [resume] 等待当前后台片段结束；挂起的协程不占用交接锁。两个底层调度器都必须异步入队。
 * UI 创建、绘制和用户审批界面继续使用原来的前台调度器，不能派到这里。
 */
class LifecycleDispatcher(
    private val foreground: CoroutineDispatcher,
    private val background: CoroutineDispatcher,
) : CoroutineDispatcher() {
    // 公平锁使恢复只需等待已开始的片段，不被连续到来的后台事件饿死。
    private val execution = ReentrantLock(true)
    private val queue = Any()
    private val tasks = ArrayDeque<Runnable>()
    private var paused = false
    private var generation = 0L
    private var scheduled = false

    override fun dispatch(context: CoroutineContext, block: Runnable) = synchronized(queue) {
        tasks.addLast(block)
        schedule()
    }

    fun pause() = switchTo(paused = true)

    fun resume() = switchTo(paused = false)

    private fun switchTo(paused: Boolean): Unit = execution.withLock {
        synchronized(queue) {
            if (this.paused == paused) return
            this.paused = paused
            generation++
            scheduled = false
            schedule()
        }
    }

    /** 调度器只持有唤醒票据，任务留在这里；暂停时已投递到 Arc 的票据不会使任务重复执行。 */
    private fun schedule() {
        if (scheduled || tasks.isEmpty()) return
        scheduled = true
        val ticket = generation
        (if (paused) background else foreground).dispatch(EmptyCoroutineContext, Runnable { drain(ticket) })
    }

    private fun drain(ticket: Long) {
        val count = synchronized(queue) {
            if (ticket != generation) return
            tasks.size
        }
        try {
            // 每次只处理进入这批时已有的任务；后续任务仍可让出前台的一帧。
            repeat(count) {
                execution.withLock {
                    val task = synchronized(queue) {
                        if (ticket != generation) return
                        tasks.removeFirst()
                    }
                    // 不持入队锁执行：RPC/PTY 读线程必须能继续投递，不能与同步写管道互相等待。
                    task.run()
                }
            }
        } finally {
            synchronized(queue) {
                if (ticket == generation) {
                    scheduled = false
                    schedule()
                }
            }
        }
    }
}
