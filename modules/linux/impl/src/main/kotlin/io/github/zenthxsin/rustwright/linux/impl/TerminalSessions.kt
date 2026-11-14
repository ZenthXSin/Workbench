package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.core.terminal.PtySession
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 终端会话列表，跨页面保留。前台在 Arc 访问，UI 暂停后与 Codex 共享串行后台状态线程。
 * 「助手」会话（[AGENT]）给 [TerminalAgentShell] 用，其余是用户自己开的。
 * 收到输出时合并通知 [pumpAll]，停帧后也继续消化，避免 PTY 缓冲写满把 shell 卡住。
 */
class TerminalSessions(
    private val env: LinuxEnvironment,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    ui: CoroutineDispatcher = ArcDispatcher,
    requestRender: () -> Unit = { TerminalView.requestRender() },
) {

    class Entry(val id: String, val title: String, val session: PtySession)

    val entries = mutableListOf<Entry>()
    var selected: Entry? = null

    /** 终端页上次的尺寸，新会话按它创建。 */
    var columns = 80
    var rows = 24
    private var counter = 0
    private val redrawPending = AtomicBoolean()
    private val pump = TerminalPump(scope, dispatcher) {
        pumpAll()
        if (redrawPending.compareAndSet(false, true)) scope.launch(ui) {
            redrawPending.set(false)
            requestRender()
        }
    }

    /** 新开一个用户会话并选中；平台不支持 PTY 时返回 null。 */
    fun create(): Entry? = start("user-${++counter}", "会话 $counter")?.also { selected = it }

    /** 助手会话（不存在或已退出时重新启动）。 */
    fun agent(): Entry? {
        entries.firstOrNull { it.id == AGENT }?.let { if (it.session.running) return it else close(it) }
        return start(AGENT, "${AppBrand.identity.assistant}")?.also { entries.remove(it); entries.add(0, it) }
    }

    private fun start(id: String, title: String): Entry? {
        val cmd = env.shellCommand() ?: return null
        val session = PtySession(cmd, columns.coerceAtLeast(20), rows.coerceAtLeast(5)).apply {
            onChanged = { pump.request() }
            start()
        }
        return Entry(id, title, session).also { entries += it }
    }

    fun close(entry: Entry) {
        entry.session.finish()
        entries.remove(entry)
        if (selected === entry) selected = entries.firstOrNull { it.id != AGENT } ?: entries.firstOrNull()
    }

    fun closeAll() {
        entries.toList().forEach { it.session.finish() }
        entries.clear()
        selected = null
    }

    fun shutdown() {
        closeAll()
        pump.close()
    }

    fun pumpAll() {
        for (e in entries) e.session.pump()
    }

    companion object {
        const val AGENT = "agent"
    }
}
