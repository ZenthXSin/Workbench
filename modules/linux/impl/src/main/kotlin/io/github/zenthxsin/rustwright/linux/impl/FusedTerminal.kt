package io.github.zenthxsin.rustwright.linux.impl

import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Shapes
import arc.math.Interp
import arc.math.Smooth
import arc.scene.Element
import arc.scene.event.Touchable
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import arc.scene.ui.layout.WidgetGroup
import arc.scene.ui.layout.Stack
import com.termux.terminal.KeyCodes
import com.termux.terminal.KeyHandler
import io.github.zenthxsin.rustwright.core.runtime.RwContext
import io.github.zenthxsin.rustwright.core.shell.AgentShell
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.HeaderAction
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.pill
import io.github.zenthxsin.rustwright.ui.kit.iconButton
import io.github.zenthxsin.rustwright.ui.widgets.Toasts
import kotlinx.coroutines.CoroutineScope

/**
 * 终端（原型「工具：终端」）：整页黑底终端，底部两行 Termux 式扩展键；
 * 右上角按钮在扩展键上方拉出虚拟机状态抽屉（CPU / 内存 / 存储 / 网络、进程）。
 * 长按终端内容选词，选区上方浮出「复制 / 全选 / 粘贴」。
 * 直接在提示符后输入：隐藏的输入框接收键盘和输入法；底部 ⌨ 键弹出 / 收起键盘，
 * 键盘开着时滑动、长按、点扩展键都不会把它收掉。
 */
class FusedTerminal(
    private val env: LinuxEnvironment,
    private val sessions: TerminalSessions,
    shell: AgentShell,
    private val ctx: RwContext,
    private val scope: CoroutineScope,
    private val onManage: () -> Unit = {},
) : WidgetGroup(), HeaderAction, io.github.zenthxsin.rustwright.ui.designsystem.BackHandler, io.github.zenthxsin.rustwright.ui.kit.OwnHeader {
    override fun onBackRequested(leave: () -> Unit): Boolean {
        if (vmOpen.target() == 0f) return false
        onHeaderAction()
        return true
    }

    private val view = TerminalView(null, RwTheme.monoAt(13f, 19f)).apply { background = BG }
    /** 选区菜单：终端固定深色，菜单也用固定深灰底、白图标（参考 Sora 编辑器的浮动工具条）。 */
    private val selMenu = Table(K.fill(K.c("3A3A3C"), 10f)).apply {
        val ink = Color.white
        add(iconButton(PI.copy, 22f, 52f, ink, stroke = 1.8f) { copySelection() }.apply { name = "复制" })
        add(iconButton(PI.selectAll, 22f, 52f, ink, stroke = 1.8f) { view.selectAll() }.apply { name = "全选" })
        add(iconButton(PI.paste, 22f, 52f, ink, stroke = 1.8f) { view.clearSelection(); pasteTerminal() }.apply { name = "粘贴" })
        margin(dp(2f), dp(6f), dp(2f), dp(6f))
        visible = false
    }
    private val input = TerminalInput { send(it) }
    private val monitor = VmMonitor(env, ctx.platform.dataDir, scope)
    private val setup = LinuxSetup(env, shell) { ctx.extensions.all(io.github.zenthxsin.rustwright.core.shell.EnvironmentPoints.ADDONS) }
    private var ctrl = false
    private var alt = false
    private var fn = false
    private var unsupported = false
    private var startError: String? = null
    private var wasInstalled = env.installed
    private val emptyLabel = K.para("正在启动终端…", 14f, K.muted, lh = 22f)
    private val empty = Table().apply {
        margin(dp(24f))
        add(emptyLabel).growX().row()
        add(pill("重试准备环境", height = 42f) { retrySetup() }).left().padTop(dp(16f))
    }

    // 原型：抽屉 transform 0.3s cubic-bezier(.2,.8,.2,1)，遮罩 opacity 0.25s ease
    private val vmOpen = Smooth(0f, 0.3f, Interp.standard)
    private val vmScrim = Smooth(0f, 0.25f, Interp.ease)
    private val body = Table()
    private val keys = Table()
    private val vm = VmPanel()
    private val vmScroll = ScrollPane(vm).apply {
        setScrollingDisabledX(true)
        setOverscroll(false, false)
    }
    private val scrim = object : Element() {
        override fun draw() {
            val a = vmScrim.get()
            if (a <= 0.001f) return
            arc.graphics.g2d.Draw.color(0f, 0f, 0f, 0.5f * a * parentAlpha)
            arc.graphics.g2d.Fill.crect(x, y, width, height)
            arc.graphics.g2d.Draw.reset()
        }
    }

    override val headerLabel get() = if (vmOpen.target() > 0f) "收起终端操作" else "终端操作与状态"

    override fun onHeaderAction() {
        val open = vmOpen.target() == 0f
        vmOpen.to(if (open) 1f else 0f)
        vmScrim.to(if (open) 1f else 0f)
        if (open) { vmScroll.scrollY = 0f; vmScroll.updateVisualScroll() }
        if (open && env.installed) monitor.start() else monitor.stop()
    }

    /** 顶栏：返回 · 终端（下面是会话状态）· 终端操作与状态。 */
    private val bar = io.github.zenthxsin.rustwright.ui.kit.PageBar("终端") {
        val s = sessions.selected?.session
        "${env.distro} · " + when {
            !env.installed -> "未安装"
            s == null -> "正在启动"
            !s.running -> "已结束"
            else -> s.title ?: "/workspace"
        }
    }

    override fun bindBack(back: () -> Unit) = bar.bindBack(back)

    init {
        bar.action(PI.gauge, "终端操作与状态") { onHeaderAction() }
        // 终端区：原型 padding 8 10，顶部 1px #232322 分隔
        view.name = "终端内容"
        body.add(Stack(view, empty)).grow().pad(dp(8f), dp(10f), dp(8f), dp(10f))
        body.userObject = io.github.zenthxsin.rustwright.ui.designsystem.KeepKeyboard
        addChild(body)
        input.color.a = 0f
        input.touchable = Touchable.disabled
        addChild(input)
        scrim.clicked { if (vmOpen.target() > 0f) onHeaderAction() }
        addChild(scrim)
        addChild(vmScroll)
        buildKeys()
        addChild(keys)
        keys.userObject = io.github.zenthxsin.rustwright.ui.designsystem.KeepKeyboard
        selMenu.userObject = io.github.zenthxsin.rustwright.ui.designsystem.KeepKeyboard
        addChild(selMenu)
        view.onSelectionChanged = { invalidate() }
        addChild(bar)
    }

    private fun focusInput() {
        if (view.session?.running != true) return notify("终端尚未启动")
        Core.scene.setKeyboardFocus(input)
        arc.scene.ui.TextField.inputBridge?.begin(input)
    }

    override fun act(delta: Float) {
        super.act(delta)
        vmOpen.update(delta)
        vmScrim.update(delta)
        val installed = env.installed
        if (installed && !wasInstalled) unsupported = false
        wasInstalled = installed
        if (view.columns > 0) { sessions.columns = view.columns; sessions.rows = view.rows }
        if (installed && sessions.selected == null && !unsupported && view.columns > 0) {
            runCatching { sessions.create() }.onFailure { startError = it.message ?: it.javaClass.simpleName }
            if (sessions.selected == null) unsupported = true
        }
        val s = sessions.selected?.session
        if (view.session !== s) view.session = s
        empty.visible = s == null
        if (s == null) emptyLabel.setText(when {
            !installed -> "${env.distro} 尚未安装完成，暂时无法启动终端。可先保存已复制的报错，再重试准备环境。"
            unsupported -> "终端启动失败：${startError ?: "此平台暂不支持交互式终端"}"
            else -> "正在启动终端…"
        })
        vm.refresh()
        invalidate()
    }

    override fun layout() {
        val kh = keys.prefHeight
        val bh = dp(io.github.zenthxsin.rustwright.ui.kit.PageBar.HEIGHT)
        bar.setBounds(0f, height - bh, width, bh)
        val h = height - bh
        keys.setBounds(0f, 0f, width, kh)
        body.setBounds(0f, kh, width, h - kh - dp(1f))
        input.setBounds(0f, kh, 1f, 1f)
        layoutSelMenu(kh, h)
        scrim.setBounds(0f, kh, width, h - kh)
        scrim.touchable = if (vmOpen.target() > 0f) Touchable.enabled else Touchable.disabled
        val vh = vm.prefHeight.coerceAtMost((h - kh - dp(12f)).coerceAtLeast(0f))
        val p = vmOpen.get()
        vmScroll.setBounds(0f, kh - (1f - p) * (vh + dp(100f)), width, vh)
        vmScroll.visible = p > 0.001f
    }

    override fun drawChildren() {
        // 终端区固定深色；顶栏跟随深浅色（浅色下黑底深字看不清）
        val barH = dp(io.github.zenthxsin.rustwright.ui.kit.PageBar.HEIGHT)
        Shapes.rect(x, y, width, height - barH, 0f, Color(BG).a(parentAlpha))
        Shapes.rect(x, y + height - barH, width, barH, 0f, Color(K.bg).a(parentAlpha))
        // 抽屉在扩展键下面滑出：先画终端和抽屉，再画扩展键盖住抽屉的起始位置
        super.drawChildren()
    }

    // ---- 扩展键（9 列 × 2 行） ----

    private fun buildKeys() {
        keys.background(K.fill(K.input, 0f))
        keys.margin(dp(2f), 0f, dp(6f), 0f)
        val list = listOf("ESC", "/", "|", "-", "HOME", "↑", "END", "PGUP", "FN", "TAB", "CTRL", "ALT", "←", "↓", "→", "PGDN", "~", "KBD")
        list.forEachIndexed { i, k ->
            val on = { (k == "CTRL" && ctrl) || (k == "ALT" && alt) || (k == "FN" && fn) }
            val t = object : Tap(null, 6f, { press(k) }) {
                override fun act(delta: Float) {
                    super.act(delta)
                    fill(if (on()) K.text else null)
                }
            }
            t.name = if (k == "KBD") "弹出键盘" else k
            if (k == "KBD") t.add(K.svg(PI.keyboard, 22f, K.text, 1.6f))
            else {
                val size = if (k.length > 3) 12f else if (k.length > 1) 13f else 17f
                val label = arc.scene.ui.Label(k, arc.scene.ui.Label.LabelStyle(RwTheme.monoAt(size, size), K.text))
                t.update { label.style.fontColor = if (on()) K.onLight else K.text }
                t.add(label)
            }
            keys.add(t).growX().uniformX().height(dp(40f)).pad(dp(1f))
            if (i % 9 == 8) keys.row()
        }
    }

    private fun press(k: String) {
        when (k) {
            "ESC" -> key(KeyCodes.KEYCODE_ESCAPE)
            "TAB" -> key(KeyCodes.KEYCODE_TAB)
            "HOME" -> key(KeyCodes.KEYCODE_MOVE_HOME)
            "END" -> key(KeyCodes.KEYCODE_MOVE_END)
            "PGUP" -> key(KeyCodes.KEYCODE_PAGE_UP)
            "PGDN" -> key(KeyCodes.KEYCODE_PAGE_DOWN)
            "↑" -> key(if (fn) KeyCodes.KEYCODE_PAGE_UP else KeyCodes.KEYCODE_DPAD_UP)
            "↓" -> key(if (fn) KeyCodes.KEYCODE_PAGE_DOWN else KeyCodes.KEYCODE_DPAD_DOWN)
            "←" -> key(if (fn) KeyCodes.KEYCODE_MOVE_HOME else KeyCodes.KEYCODE_DPAD_LEFT)
            "→" -> key(if (fn) KeyCodes.KEYCODE_MOVE_END else KeyCodes.KEYCODE_DPAD_RIGHT)
            "CTRL" -> ctrl = !ctrl
            "ALT" -> alt = !alt
            "FN" -> fn = !fn
            "KBD" -> if (Core.scene.keyboardFocus === input) Core.scene.setKeyboardFocus(null) else focusInput()
            else -> send(k)
        }
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
        ctrl = false; alt = false; fn = false
        view.scrollToBottom()
    }

    // ---- 虚拟机状态抽屉 ----

    private fun retrySetup() {
        val p = ctx.services.getOrNull<LinuxProvisioner>()
        if (env.installed) { unsupported = false; startError = null }
        if (p != null && !p.ready) { p.start(); notify("正在重新准备环境") }
    }

    private fun notify(text: String) {
        ctx.services.getOrNull<ProjectContext>()?.notify(text) ?: Toasts.show(text)
    }

    private fun copySelection() {
        val text = view.selectionText()
        view.clearSelection()
        if (text.isBlank()) return notify("选中的内容是空的")
        ctx.platform.setClipboardText(text)
        notify("已复制")
    }

    /** 菜单放在选区上方（放不下就放到选区下方、拖柄下面），水平居中并夹在屏幕内；拖拖柄时先藏起来。 */
    private fun layoutSelMenu(kh: Float, h: Float) {
        val b = view.selectionBounds()
        val show = b != null && !view.draggingHandle && vmOpen.target() == 0f
        if (!show) { selMenu.visible = false; return }
        if (!selMenu.visible) {
            selMenu.visible = true
            selMenu.clearActions()
            selMenu.color.a = 0f
            selMenu.actions(arc.scene.actions.Actions.fadeIn(0.12f, arc.math.Interp.smooth))
        }
        selMenu.pack()
        val p = view.localToAscendantCoordinates(this, arc.math.geom.Vec2(b!![0], b[1]))
        val q = view.localToAscendantCoordinates(this, arc.math.geom.Vec2(b[2], b[3]))
        val gap = dp(10f)
        var my = p.y + gap
        if (my + selMenu.height > h - dp(4f)) my = q.y - dp(26f) - gap - selMenu.height
        my = my.coerceIn(kh + dp(4f), (h - selMenu.height - dp(4f)).coerceAtLeast(kh + dp(4f)))
        val mx = ((p.x + q.x) / 2f - selMenu.width / 2f).coerceIn(dp(8f), (width - selMenu.width - dp(8f)).coerceAtLeast(dp(8f)))
        selMenu.setPosition(mx, my)
    }

    private fun pasteTerminal() {
        val s = view.session?.takeIf { it.running } ?: return notify("终端尚未启动")
        val text = ctx.platform.clipboardText()?.takeIf { it.isNotEmpty() } ?: return notify("剪贴板为空")
        ctrl = false; alt = false
        view.scrollToBottom()
        s.emulator.paste(text)
        if (vmOpen.target() > 0f) onHeaderAction()
        notify("已粘贴到终端")
    }

    private inner class VmPanel : Table() {
        private val uptime = K.text("", 12f, K.subtle, lh = 12f)
        private val environmentStatus = K.text("", 14f, K.text, 600, 14f)
        private val stats = List(4) { Stat() }
        private val procRows = Table()
        private val setupStatus = Table()
        private var setupSignature = ""
        private var shownProcs: List<VmMonitor.Proc>? = null

        init {
            touchable = Touchable.enabled
            top()
            add(Handle()).size(dp(40f), dp(4f)).padTop(dp(8f)).row()
            val col = Table()
            col.margin(dp(10f), dp(14f), dp(14f), dp(14f))
            col.add(setupStatus).growX().row()
            val head = Table()
            head.add(Dot(K.green)).size(dp(8f)).padRight(dp(8f))
            head.add(environmentStatus)
            head.add().growX()
            head.add(uptime)
            col.add(head).growX().padTop(dp(6f)).row()
            val grid = Table()
            stats.forEachIndexed { i, st ->
                grid.add(st).growX().uniformX().pad(0f, if (i % 2 == 0) 0f else dp(5f), if (i < 2) dp(10f) else 0f, if (i % 2 == 0) dp(5f) else 0f)
                if (i % 2 == 1) grid.row()
            }
            col.add(grid).growX().padTop(dp(12f)).row()
            col.add(K.text("进程（按 CPU）", 12f, K.subtle, lh = 12f)).left().padTop(dp(12f)).row()
            col.add(procRows).growX().padTop(dp(6f)).row()
            add(col).growX()
        }

        override fun drawBackground(x: Float, y: Float) {
            val a = parentAlpha * color.a
            Shapes.shadow(x, y, width, height, dp(18f), 0f, dp(10f), dp(30f), 0f, Color(0f, 0f, 0f, 0.4f * a))
            Shapes.rect(x, y, width, height, dp(18f), dp(18f), 0f, 0f, Color(K.bg).a(a), 0f, null)
        }

        private fun outline(text: String, onClick: () -> Unit) = Tap(null, 10f, onClick).apply {
            border = K.inputBorder
            name = text
            add(K.text(text, 14f, K.text, lh = 14f))
        }

        fun refresh() {
            if (!vmScroll.visible) return
            val provisioner = ctx.services.getOrNull<LinuxProvisioner>()
            val signature = "${provisioner?.ready}|${provisioner?.running}|${provisioner?.error}|${provisioner?.detail}"
            if (signature != setupSignature) {
                setupSignature = signature
                setupStatus.clearChildren()
                if (provisioner != null && !provisioner.ready) {
                    setupStatus.add(K.para(provisioner.error?.let { "环境准备失败：$it" }
                        ?: if (provisioner.running) "正在准备环境：${provisioner.detail}" else "环境尚未准备完成", 13f,
                        if (provisioner.error != null) K.danger else K.subtle, lh = 20f)).growX().padTop(dp(12f)).row()
                    val actions = Table()
                    actions.add(outline(if (provisioner.running) "准备进行中" else "重试准备环境") { retrySetup() })
                        .growX().uniformX().height(dp(42f)).padRight(dp(4f))
                    actions.add(outline("复制准备日志") {
                        ctx.platform.setClipboardText(provisioner.failureReport())
                        onHeaderAction()
                        notify("准备日志已复制")
                    }).growX().uniformX().height(dp(42f)).padLeft(dp(4f))
                    setupStatus.add(actions).growX().padTop(dp(8f))
                }
            }
            val up = (System.currentTimeMillis() - VmMonitor.bootAt) / 60_000
            environmentStatus.setText(env.distro + when {
                !env.installed -> " · 未安装完成"
                view.session?.running == true -> " · 运行中"
                else -> " · 终端未启动"
            })
            uptime.setText(if (env.installed) "已运行 ${up / 60} 小时 ${up % 60} 分" else "")
            val m = monitor
            stats[0].set("CPU", "${Runtime.getRuntime().availableProcessors()} 核", m.cpu?.let { "${it.toInt()}%" }, (m.cpu ?: 0f) / 100f, if ((m.cpu ?: 0f) > 80f) K.danger else K.accent)
            stats[1].set("内存", m.memTotalGb?.let { "共 %.0f GB".format(it) } ?: "", m.memUsedGb?.let { "%.1f GB".format(it) }, (m.memUsedGb ?: 0f) / (m.memTotalGb ?: 1f), K.c("7FA9D6"))
            stats[2].set("存储", m.diskFreeGb?.let { "可用 %.0f GB".format(it) } ?: "", m.diskUsedGb?.let { "%.1f GB".format(it) }, (m.diskUsedGb ?: 0f) / ((m.diskUsedGb ?: 0f) + (m.diskFreeGb ?: 1f)), K.green)
            stats[3].set("网络", "下行", m.netMbps?.let { "%.1f MB/s".format(it) }, (m.netMbps ?: 0f) / 3.2f, K.amber)
            if (m.procs != shownProcs) {
                shownProcs = m.procs
                procRows.clearChildren()
                for (p in m.procs) {
                    val r = Table()
                    r.add(K.mono(p.name, 12f, K.text, 18f).apply { setEllipsis(true) }).growX().minWidth(0f).left()
                    r.add(K.mono("%.1f%%".format(p.cpu), 12f, K.muted, 18f).apply { setAlignment(arc.util.Align.right) }).width(dp(48f)).padLeft(dp(10f))
                    r.add(K.mono("${p.memMb} MB", 12f, K.muted, 18f).apply { setAlignment(arc.util.Align.right) }).width(dp(60f)).padLeft(dp(10f))
                    procRows.add(r).growX().padBottom(dp(6f)).row()
                }
            }
        }
    }

    private fun restart() {
        sessions.closeAll()
        view.session = null
        unsupported = false
        Core.app.post { runCatching { sessions.create() } }
    }

    /** 一张统计卡：标签 / 说明、数值、细进度条。 */
    private class Stat : Table(K.fill(K.surface, 10f)) {
        private val label = K.text("", 12f, K.subtle, lh = 12f)
        private val detail = K.text("", 12f, K.subtle, lh = 12f)
        private val value = K.text("—", 18f, K.text, 600, 18f)
        private val bar = Bar()

        init {
            margin(dp(10f), dp(12f), dp(10f), dp(12f))
            val top = Table()
            top.add(label).growX().left()
            top.add(detail)
            add(top).growX().row()
            add(value).left().padTop(dp(8f)).row()
            add(bar).growX().height(dp(4f)).padTop(dp(8f))
        }

        fun set(l: String, d: String, v: String?, frac: Float, c: Color) {
            if (label.text.toString() != l) label.setText(l)
            if (detail.text.toString() != d) detail.setText(d)
            val vv = v ?: "—"
            if (value.text.toString() != vv) value.setText(vv)
            bar.target = frac.coerceIn(0f, 1f)
            bar.fill = c
        }
    }

    private class Bar : Element() {
        var target = 0f
        var fill: Color = K.accent
        // 原型：width 0.6s ease
        private val shown = Smooth(0f, 0.6f, Interp.ease)

        override fun act(delta: Float) {
            super.act(delta)
            shown.to(target).update(delta)
        }

        override fun draw() {
            val a = parentAlpha
            Shapes.rect(x, y, width, height, height / 2f, Color(K.line).a(a))
            val w = width * shown.get()
            if (w > 0.5f) Shapes.rect(x, y, w.coerceAtLeast(height), height, height / 2f, Color(fill).a(a))
        }
    }

    private class Dot(private val c: Color) : Element() {
        override fun draw() = Shapes.circle(x + width / 2f, y + height / 2f, width / 2f, Color(c).a(parentAlpha))
    }

    private class Handle : Element() {
        override fun draw() = Shapes.rect(x, y, width, height, height / 2f, Color(K.inputBorder).a(parentAlpha))
    }

    companion object {
        val BG: Color = K.c("0B0B0A")
    }
}
