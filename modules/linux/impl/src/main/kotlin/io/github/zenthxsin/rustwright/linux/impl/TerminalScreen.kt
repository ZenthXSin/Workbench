package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.scene.ui.Label
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import com.termux.terminal.KeyCodes
import com.termux.terminal.KeyHandler
import io.github.zenthxsin.rustwright.core.runtime.Platform
import io.github.zenthxsin.rustwright.core.runtime.RwContext
import io.github.zenthxsin.rustwright.core.shell.AgentShell
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import io.github.zenthxsin.rustwright.ui.designsystem.Icons
import io.github.zenthxsin.rustwright.ui.designsystem.Rw
import io.github.zenthxsin.rustwright.ui.designsystem.RwButton
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import io.github.zenthxsin.rustwright.ui.widgets.HorizontalScroll
import io.github.zenthxsin.rustwright.ui.widgets.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 终端页：proot 里的 Alpine 交互式 shell（完整 PTY，可跑 vim/top 等），可开多个会话。
 * 「助手」会话显示助手执行的命令和文件操作。首次安装后自动初始化（软件、MCP 服务器）。
 * 底部是附加键（Esc、Tab、Ctrl、方向键…）和输入框（支持中文输入法）。
 */
class TerminalScreen(
    private val env: LinuxEnvironment,
    private val sessions: TerminalSessions,
    shell: AgentShell,
    ctx: RwContext,
    private val scope: CoroutineScope,
) : Table() {

    private val platform: Platform = ctx.platform
    private val setup = LinuxSetup(env, shell)
    private val view = TerminalView(null)
    private val status = Label("", RwTheme.Styles.caption).apply { setEllipsis(true) }
    private val badge = Label("", RwTheme.Styles.caption)
    private val tabs = Table().left()
    private var tabsVersion = -1
    private var ctrl = false
    private var alt = false
    private var job: Job? = null
    private var jobLabel = ""
    private val log = StringBuilder()
    private var unsupported = false
    private val input = TerminalInput { send(it) }
    private val main = Table()
    private var showingTerminal: Boolean? = null

    init {
        top()
        add(header()).growX().row()
        add(Rw.divider()).growX().height(dp(1f)).row()
        add(main).grow()
        // 点终端区域：把焦点给输入框并弹出键盘（拖动用于翻看历史，不触发）
        view.clicked {
            arc.Core.scene.setKeyboardFocus(input)
            arc.scene.ui.TextField.inputBridge?.begin(input)
        }

        update {
            val installed = env.installed && job?.isActive != true
            if (showingTerminal != installed) { showingTerminal = installed; rebuildMain(installed) }
            if (view.columns > 0) { sessions.columns = view.columns; sessions.rows = view.rows }
            if (installed && sessions.selected == null && !unsupported && view.columns > 0) {
                runCatching { sessions.create() }.onFailure { Toasts.show("启动终端失败：${it.message}") }
                if (sessions.selected == null) unsupported = true
            }
            val s = sessions.selected?.session
            if (view.session !== s) view.session = s
            rebuildTabs()
            val (text, color) = when {
                job?.isActive == true -> "进行中" to RwTheme.Colors.warning
                !env.installed -> "未安装" to RwTheme.Colors.muted
                s != null && s.running && setup.done -> "运行中" to RwTheme.Colors.success
                !setup.done -> "未初始化" to RwTheme.Colors.warning
                else -> "已结束" to RwTheme.Colors.muted
            }
            badge.setText(text)
            badge.color.set(color)
            status.setText(when {
                job?.isActive == true -> jobLabel + "：" + (log.lineSequence().lastOrNull { it.isNotBlank() } ?: "…")
                !env.installed -> "${env.distro} 未安装"
                unsupported -> "此平台不支持交互式终端"
                s == null -> "正在启动…"
                !s.running -> "会话已结束（退出码 ${s.exitCode}）"
                !setup.done -> "还没初始化软件和 MCP 服务器（右上角菜单 - 初始化）"
                else -> (s.title ?: "${env.distro} · /workspace")
            })
        }
    }

    private fun header() = Rw.topBar(
        "终端",
        titleElement = Table().left().apply {
            add(Table().left().apply {
                add(Rw.title("终端"))
                add(badge).padLeft(dp(6f))
            }).left().row()
            add(status).left().growX().minWidth(0f)
        },
        actions = listOf(
            Rw.icon(Icons.copy, "复制") {
                view.session?.emulator?.screen?.transcriptText?.let { platform.setClipboardText(it.takeLast(20_000)); Toasts.show("已复制终端内容") }
            },
            Rw.icon(Icons.paste, "粘贴") { platform.clipboardText()?.let { view.session?.emulator?.paste(it) } },
            Rw.icon(Icons.more, "更多") { menu() },
        ),
    ).apply { background(RwTheme.solid(RwTheme.Colors.surface)) }

    /** 更多操作：新会话、关闭会话、初始化、重装。 */
    private fun menu() {
        scope.launch {
            val actions = buildList {
                if (env.installed) { add("新会话"); add("关闭当前会话") }
                if (env.installed && env.managed) add(if (setup.done) "重新初始化（更新软件和 MCP）" else "初始化")
                if (env.managed) add(if (env.installed) "重装 ${env.distro}" else "安装 ${env.distro}")
            }
            if (actions.isEmpty()) return@launch
            val pick = actions.getOrNull(Dialogs.choose("终端", null, actions, highlight = -1)) ?: return@launch
            withContext(ArcDispatcher) {
                when {
                    pick == "新会话" -> sessions.create()
                    pick == "关闭当前会话" -> sessions.selected?.let { if (it.id == TerminalSessions.AGENT) Toasts.show("${AppBrand.identity.assistant}会话不能关闭") else sessions.close(it) }
                    pick.contains("初始化") -> runSetup()
                    pick.startsWith("重装") -> scope.launch {
                        if (Dialogs.confirm("重装 ${env.distro}", "会删除 Linux 里的全部内容（工作区 /workspace 不受影响）。", ok = "重装")) withContext(ArcDispatcher) { install() }
                    }
                    pick.startsWith("安装") -> install()
                }
            }
        }
    }

    /** 已安装：会话标签 + 终端 + 附加键 + 输入框；未安装：说明和安装按钮。 */
    private fun rebuildMain(installed: Boolean) {
        main.clearChildren()
        if (!installed) {
            val busy = job?.isActive == true
            main.add(Rw.empty(
                Icons.terminal,
                if (busy) "正在准备 ${env.distro}，过程见上方状态…" else "内置 ${env.distro}：${AppBrand.identity.assistant}在这里执行命令、读写文件，也可以自己用。首次安装约下载 30 MB，然后自动安装 node、python 和 MCP 服务器。",
                if (busy || !env.managed) null else Rw.primary("安装 ${env.distro}", Icons.download) { install() },
            )).expand().center()
            return
        }
        main.add(Table(RwTheme.solid(RwTheme.Colors.surface)).left().apply {
            margin(dp(6f), dp(9f), dp(6f), dp(9f))
            add(io.github.zenthxsin.rustwright.ui.widgets.HorizontalScroll(tabs)).growX().minWidth(0f)
        }).growX().row()
        main.add(Rw.divider()).growX().height(dp(1f)).row()
        main.add(Table(RwTheme.round(14, TerminalView.TERMINAL_BG)).apply {
            margin(dp(8f))
            add(view).grow()
        }).grow().pad(dp(9f)).row()
        main.add(extraKeys()).growX().padLeft(dp(9f)).padRight(dp(9f)).row()
        main.add(input).growX().height(dp(38f)).pad(dp(6f), dp(9f), dp(9f), dp(9f))
    }

    /** 会话标签：点击切换；「+」新建。会话增减时重建。 */
    private fun rebuildTabs() {
        val version = sessions.entries.fold(sessions.entries.size) { acc, e -> acc * 31 + e.hashCode() }
        if (version == tabsVersion) return
        tabsVersion = version
        tabs.clearChildren()
        sessions.entries.forEach { e ->
            tabs.add(RwButton(e.title, if (e.id == TerminalSessions.AGENT) Icons.agent else null, RwTheme.Styles.chip).apply {
                margin(0f, dp(9f), 0f, dp(9f))
                update { isChecked = sessions.selected === e }
                clicked { sessions.selected = e; view.scrollToBottom() }
            }).height(dp(28f)).padRight(dp(5f))
        }
        tabs.add(Rw.icon(Icons.add, "新会话") {
            if (!env.installed) return@icon Toasts.show("先安装 ${env.distro}")
            sessions.create()
        }).size(dp(28f))
    }

    private fun send(text: String) {
        val s = view.session ?: return
        view.scrollToBottom()
        if ((ctrl || alt) && text.codePointCount(0, text.length) == 1) {
            s.writeCodePoint(text.codePointAt(0), ctrl, alt)
            ctrl = false; alt = false
        } else s.write(text)
    }

    private fun key(code: Int) {
        val s = view.session ?: return
        var mode = 0
        if (ctrl) mode = mode or KeyHandler.KEYMOD_CTRL
        if (alt) mode = mode or KeyHandler.KEYMOD_ALT
        KeyHandler.getCode(code, mode, s.emulator.isCursorKeysApplicationMode, s.emulator.isKeypadApplicationMode)?.let { s.write(it) }
        ctrl = false; alt = false
        view.scrollToBottom()
    }

    private fun extraKeys(): HorizontalScroll {
        val row = Table().left()
        fun k(text: String, action: () -> Unit) = row.add(RwButton(text, style = RwTheme.Styles.chip).apply {
            label.style.font = RwTheme.mono
            clicked(action)
        }).minWidth(dp(38f)).height(dp(34f)).padRight(dp(5f))
        k("Esc") { key(KeyCodes.KEYCODE_ESCAPE) }
        k("Tab") { key(KeyCodes.KEYCODE_TAB) }
        row.add(RwButton("Ctrl", style = RwTheme.Styles.chip).apply { update { isChecked = ctrl }; clicked { ctrl = !ctrl } }).minWidth(dp(42f)).height(dp(34f)).padRight(dp(5f))
        row.add(RwButton("Alt", style = RwTheme.Styles.chip).apply { update { isChecked = alt }; clicked { alt = !alt } }).minWidth(dp(38f)).height(dp(34f)).padRight(dp(5f))
        k("←") { key(KeyCodes.KEYCODE_DPAD_LEFT) }
        k("↓") { key(KeyCodes.KEYCODE_DPAD_DOWN) }
        k("↑") { key(KeyCodes.KEYCODE_DPAD_UP) }
        k("→") { key(KeyCodes.KEYCODE_DPAD_RIGHT) }
        k("退格") { key(KeyCodes.KEYCODE_DEL) }
        k("^C") { view.session?.writeCodePoint('c'.code, ctrl = true) }
        k("^D") { view.session?.writeCodePoint('d'.code, ctrl = true) }
        k("Home") { key(KeyCodes.KEYCODE_MOVE_HOME) }
        k("End") { key(KeyCodes.KEYCODE_MOVE_END) }
        k("PgUp") { key(KeyCodes.KEYCODE_PAGE_UP) }
        k("PgDn") { key(KeyCodes.KEYCODE_PAGE_DOWN) }
        return HorizontalScroll(row)
    }

    private fun install() {
        if (job?.isActive == true) return
        sessions.closeAll()
        view.session = null
        log.setLength(0)
        jobLabel = "安装 ${env.distro}"
        job = scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { env.install { line -> log.appendLine(line) } } }
            withContext(ArcDispatcher) { r.exceptionOrNull()?.let { Toasts.show("安装失败：${it.message}") } }
            if (r.isSuccess) setupNow()
        }
    }

    private fun runSetup() {
        if (job?.isActive == true) return
        job = scope.launch { setupNow() }
    }

    /** 在助手会话里跑初始化，并切到这个会话让用户看到过程。 */
    private suspend fun setupNow() {
        log.setLength(0)
        jobLabel = "初始化"
        log.appendLine("安装软件和 MCP 服务器，过程见「${AppBrand.identity.assistant}」会话")
        withContext(ArcDispatcher) { sessions.agent()?.let { sessions.selected = it } }
        val r = runCatching { setup.run() }
        withContext(ArcDispatcher) { r.fold({ Toasts.show(it, 5f) }, { Toasts.show(it.message ?: "初始化失败", 5f) }) }
    }
}
