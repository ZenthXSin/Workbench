package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Shapes
import arc.input.KeyCode
import arc.math.Interp
import arc.math.Smooth
import arc.scene.Element
import arc.scene.event.Touchable
import arc.scene.ui.TextArea
import arc.scene.ui.TextField
import io.github.zenthxsin.rustwright.ui.kit.CssBox
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.iconButton

/**
 * 输入框（原型 composer）：
 * - 空闲：上面一行输入，下面一行「+ / 模型·强度 / 弹性 / 发送」
 * - 助手工作中且没在打字：收成一行「+ / 输入 / 停止」，模型按钮收起；往上翻看旧消息时也收成这一行；
 *   有内容时停止键左边滑出「插入」按钮，回车即插入消息（不打断）
 */
class Composer(
    private val running: () -> Boolean,
    /** 正在往上翻看旧消息：没在打字时也收成一行，少挡内容。 */
    private val readingBack: () -> Boolean = { false },
    /** 在对话页（空闲时占位是「回复助手」，首页是「和助手说点什么」）。 */
    private val inChat: () -> Boolean,
    private val modelLabel: () -> String,
    /** 返回是否已接收消息；环境或项目未就绪时保留草稿。 */
    private val onSend: (String) -> Boolean,
    private val onSteer: (String) -> Boolean,
    private val onStop: () -> Unit,
    private val onAttach: () -> Unit,
    private val onModel: () -> Unit,
    private val onCommand: (String) -> Boolean = { false },
) : Table() {

    private fun style() = TextField.TextFieldStyle().apply {
        font = RwTheme.text(16f, 400)
        fontColor = K.text
        messageFont = RwTheme.text(16f, 400)
        messageFontColor = K.subtle
        cursor = RwTheme.solid(K.text).also { it.minWidth = dp(1.5f) }
        selection = RwTheme.solid(K.blue.cpy().a(0.3f))
    }

    /** 两行布局时的输入框：多行，随内容长高，最多 [MAX_LINES] 行，再多就在框里滚动。 */
    val input: TextArea = object : TextArea("", style()) {
        // 框长高后能显示更多行：滚动位置往回拉，上面的行不要被卷走、下面也不留空白
        override fun sizeChanged() {
            super.sizeChanged()
            firstLineShowing = firstLineShowing.coerceAtMost(lines - linesShowing).coerceAtLeast(0)
        }
    }
    /** 一行布局时的输入框（与 [input] 内容同步）。 */
    private val inline: TextField = TextField("", style())
    private lateinit var inputCell: arc.scene.ui.layout.Cell<TextArea>
    private var inputLines = 1

    private val pad = Smooth(dp(12f), 0.4f, Interp.standard)
    private val topRow: CssBox
    private val inlineRow: Table
    private val inlineAlpha = Smooth(0f, 0.3f, Interp.ease).delay(0.1f)
    private val model: CssBox
    private val modelText = K.text("", 14f, K.text)
    private val effortText = K.text("", 14f, K.muted)
    private val primary = PrimaryButton()
    private var focus = false
    /** 已添加的附件：本机文件 + 相对项目目录的路径（发给助手的就是这个路径）。 */
    private val attachments = ArrayList<Pair<java.io.File, String>>()
    private val attachRow = Table().left()
    private val commandRows = Table().top()
    private val commandPane: arc.scene.ui.ScrollPane = object : arc.scene.ui.ScrollPane(commandRows) {
        // 内容省略以适应输入框宽度，不能撑宽外壳（Reveal 的最小宽度使用首选宽度）。
        override fun getPrefWidth() = 0f
    }.apply {
        name = "指令列表"; setScrollingDisabledX(true); setOverscroll(false, false)
    }
    private lateinit var commandCell: arc.scene.ui.layout.Cell<arc.scene.ui.ScrollPane>
    private var commandQuery: String? = null
    private var commandHeight = -1f

    init {
        touchable = Touchable.childrenOnly
        input.setMessageText("和${AppBrand.identity.assistant}说点什么")
        inline.setMessageText("将消息加入队列…")
        val sync = { from: TextField, to: TextField -> if (to.text != from.text) to.setText(from.text) }
        input.changed { sync(input, inline) }
        inline.changed { sync(inline, input) }
        // 多行输入框里回车就是换行（发送用右下角按钮）；一行模式下回车仍直接加入队列
        inline.setTextFieldListener { _, c -> if (c == '\n' || c == '\r') submit() }

        // 原型（App.dc.html 输入框）的各项过渡：
        // 上面一行：max-height 60↔0 0.4s cubic-bezier(.2,.8,.2,1)，opacity 0.2s ease
        topRow = CssBox(Table().apply { inputCell = add(input).growX().height(dp(30f)).pad(dp(2f), dp(8f), dp(12f), dp(8f)) }, horizontal = false,
            maxShown = dp(30f) + lineHeight() * (MAX_LINES - 1) + dp(14f), sizeDuration = 0.4f, alphaDuration = 0.2f)
        // 一行模式的输入区：一直占着中间的弹性空间，只有 opacity 0.3s ease（延迟 0.1s）
        inlineRow = object : Table() {
            override fun getPrefWidth() = 0f
            override fun draw() {
                val old = color.a
                color.a = old * inlineAlpha.get()
                if (color.a > 0.001f) super.draw()
                color.a = old
            }
        }.apply { add(inline).growX().height(dp(30f)).padLeft(dp(10f)).padRight(dp(6f)) }

        val modelPill = Tap(K.surface2, 20f) { onModel() }.apply {
            // 长模型名（deepseek-v4.1-flash）截断，按钮整体不超过原型的 max-width 200
            add(modelText.apply { setEllipsis(true) }).minWidth(0f).maxWidth(dp(120f))
            add(effortText).padLeft(dp(6f))
            margin(0f, dp(14f), 0f, dp(14f))
        }
        // 模型按钮：max-width 200↔0、margin-left 8↔0、scale 1↔0.85（0.4s 曲线），opacity 0.25s ease，原点左中
        model = CssBox(modelPill, horizontal = true, maxShown = dp(200f), sizeDuration = 0.4f, alphaDuration = 0.25f,
            hiddenScale = 0.85f, scaleDuration = 0.4f, origin = arc.util.Align.left, marginBefore = dp(8f))

        val bottom = Table()
        bottom.add(iconButton(PI.plus, 20f, 40f, fill = K.surface2, stroke = 1.8f) { onAttach() }).size(dp(40f))
        bottom.add(model).height(dp(40f))
        // 弹性空白 / 一行模式的输入区
        // 只吃剩余空间：自身首选宽度为 0，窄屏时不和模型按钮抢宽度
        bottom.add(inlineRow).height(dp(40f)).growX().minWidth(0f)
        // 输入法开着时点发送 / 插入：不先收键盘（收键盘会让输入框下移，这次点击就落空了），发完也不收
        primary.userObject = io.github.zenthxsin.rustwright.ui.designsystem.KeepKeyboard
        bottom.add(primary).size(dp(40f)).padLeft(dp(8f))

        commandCell = add(commandPane).growX().height(0f)
        row()
        add(attachRow).growX().left().row()
        add(topRow).growX().row()
        add(bottom).growX()
        applyPad()
    }

    private val hasText get() = input.text.isNotBlank()
    private val hasContent get() = hasText || attachments.isNotEmpty()

    /** 加一个附件（已经复制进项目 tmp/），显示在输入框上方。 */
    fun addAttachment(file: java.io.File, path: String) {
        attachments += file to path
        rebuildAttachments()
    }

    private fun rebuildAttachments() {
        attachRow.clearChildren()
        if (attachments.isEmpty()) { invalidateHierarchy(); return }
        val strip = Table().left()
        for ((file, path) in attachments.toList()) strip.add(attachmentChip(file, path)).padRight(dp(8f))
        attachRow.add(arc.scene.ui.ScrollPane(strip).apply { setScrollingDisabledY(true); setOverscroll(true, false) })
            .growX().height(dp(64f)).padBottom(dp(8f))
        invalidateHierarchy()
    }

    /** 图片是 56 方形缩略图，其他文件是图标 + 文件名；右上角小叉删掉。 */
    private fun attachmentChip(file: java.io.File, path: String): Element {
        val body: Table = if (Attachments.isImage(path)) Table(K.fill(K.surface2, 12f)).apply {
            add(io.github.zenthxsin.rustwright.ui.kit.ImageThumb(file)).size(dp(56f))
        } else Table(K.fill(K.surface2, 12f)).apply {
            margin(0f, dp(12f), 0f, dp(12f))
            add(K.svg(PI.fileUp, 18f, K.muted, 1.6f)).padRight(dp(8f))
            add(K.text(file.name.substringAfter('-').substringAfter('-'), 14f, K.text, lh = 14f).apply { setEllipsis(true) }).maxWidth(dp(140f)).minWidth(0f)
        }
        val close = Tap(K.dl("3A3A38", "D6D5CF"), 10f) {
            attachments.removeAll { it.second == path }
            rebuildAttachments()
        }.apply {
            name = "移除附件"
            add(K.svg(PI.close, 10f, K.text, 2.2f))
        }
        return arc.scene.ui.layout.Stack().apply {
            add(Table().apply { add(body).height(dp(56f)).padTop(dp(4f)).padRight(dp(4f)) })
            add(Table().top().right().apply { add(close).size(dp(20f)) })
        }
    }

    private fun submit() {
        val text = input.text.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        if (SlashCommands.isCommand(text)) {
            if (attachments.isNotEmpty()) { io.github.zenthxsin.rustwright.ui.widgets.Toasts.show("指令不能携带附件，请先移除附件。"); return }
            if (onCommand(text)) { input.setText(""); inline.setText("") }
            return
        }
        val message = Attachments.compose(text, attachments.map { it.second })
        val wasRunning = running()
        val accepted = if (wasRunning) onSteer(message) else onSend(message)
        if (!accepted) return
        input.setText("")
        inline.setText("")
        attachments.clear()
        rebuildAttachments()
    }

    override fun act(delta: Float) {
        super.act(delta)
        updateCommands()
        val run = running()
        // 点进一行模式的输入区：展开成两行并把焦点交给上面的输入框
        // 输入法要跟着交给上面的输入框：只换焦点的话，一行输入框失焦会把刚弹出的键盘收回去（闪一下就没了）
        if (Core.scene?.keyboardFocus === inline) {
            Core.scene?.setKeyboardFocus(input)
            input.cursorPosition = input.text.length
            arc.scene.ui.TextField.inputBridge?.begin(input)
        }
        focus = Core.scene?.keyboardFocus === input
        // 按实际行数（含自动折行）长高：1 行 30dp，每多一行加一个行高，最多 MAX_LINES 行，再多在框里滚动
        val lines = if (input.text.isEmpty()) 1 else input.lines.coerceIn(1, MAX_LINES)
        if (lines != inputLines) {
            inputLines = lines
            inputCell.height(dp(30f) + lineHeight() * (lines - 1))
            input.invalidateHierarchy()
        }
        // 运行中、或正在往上翻看旧消息，且没在打字 → 收成一行
        val compact = (run || readingBack()) && !focus
        if (topRow.shown == compact) topRow.setShown(!compact)
        inlineAlpha.to(if (compact) 1f else 0f).update(delta)
        inline.touchable = if (compact) Touchable.enabled else Touchable.disabled
        if (model.shown == compact) model.setShown(!compact)
        input.setMessageText(if (run) "将消息加入队列…" else if (inChat()) "回复${AppBrand.identity.assistant}，输入 / 查看指令" else "和${AppBrand.identity.assistant}说点什么，输入 / 查看指令")
        inline.setMessageText(if (run) "将消息加入队列…" else "回复${AppBrand.identity.assistant}")
        val (m, e) = modelLabel().split('|').let { it[0] to it.getOrElse(1) { "" } }
        if (modelText.text.toString() != m) modelText.setText(m)
        if (effortText.text.toString() != e) effortText.setText(e)
        pad.to(if (compact) dp(6f) else dp(12f)).update(delta)
        applyPad()
        // 运行中输入了内容：停止键直接变回发送（加入队列 / 插话），清空后再变回停止
        val stopMode = run && !hasContent
        primary.running = stopMode
        primary.active = hasContent
        primary.onTap = { if (stopMode) onStop() else submit() }
    }

    private companion object { const val MAX_LINES = 5 }

    private fun updateCommands() {
        val text = input.text
        val commands = SlashCommands.suggestions(text)
        val menuHeight = if (commands.isEmpty()) 0f else minOf(dp(280f), Core.scene.height * 0.32f, dp(62f) * commands.size)
        if (commandQuery == text && commandHeight == menuHeight) return
        commandHeight = menuHeight
        commandQuery = text
        commandRows.clearChildren()
        commandPane.visible = commands.isNotEmpty()
        commandCell.height(menuHeight)
        for (command in commands) {
            val row = io.github.zenthxsin.rustwright.ui.kit.listRow("/${command.name}", command.description, command.icon,
                trailing = "/${command.english}", minHeight = 62f) {
                if (onCommand("/${command.name}")) { input.setText(""); inline.setText("") }
            }
            row.name = "指令:${command.name}"
            row.userObject = io.github.zenthxsin.rustwright.ui.designsystem.KeepKeyboard
            commandRows.add(row).growX().minWidth(0f).row()
        }
        commandPane.setScrollYForce(0f)
        invalidateHierarchy()
    }

    private fun lineHeight() = input.style.font.lineHeight

    private var appliedPad = -1f

    private fun applyPad() {
        val p = pad.get()
        if (p == appliedPad) return
        appliedPad = p
        // 上边距 12 → 6，左右下 10 → 6（两行 ↔ 一行）
        val side = dp(6f) + (dp(4f)) * ((p - dp(6f)) / dp(6f)).coerceIn(0f, 1f)
        margin(p, side, side, side)
        invalidateHierarchy()
    }

    override fun drawBackground(x: Float, y: Float) {
        val a = color.a * parentAlpha
        Shapes.rect(x, y, width, height, dp(24f), Color(K.input).a(a), dp(1f), Color(K.inputBorder).a(a))
    }

    /** 发送 / 停止合一的按钮：上箭头与空心方框交叉淡入淡出，底色平滑过渡。 */
    private class PrimaryButton : Element() {
        var running = false
        var active = false
        var onTap: () -> Unit = {}
        // 原型：底色 / 前景 0.25s ease；箭头与停止方框 opacity 0.2s ease、scale 0.5↔1 0.3s cubic-bezier(.2,.8,.2,1)
        private val bgMix = Smooth(0f, 0.25f, Interp.ease)
        private val fgMix = Smooth(0f, 0.25f, Interp.ease)
        private val arrowA = Smooth(1f, 0.2f, Interp.ease)
        private val arrowS = Smooth(1f, 0.3f, Interp.standard)
        private val stopA = Smooth(0f, 0.2f, Interp.ease)
        private val stopS = Smooth(0.5f, 0.3f, Interp.standard)
        private val arrow = K.svg(PI.arrowUp, 19f, K.text, 2f)

        init {
            touchable = Touchable.enabled
            name = "发送"
            clicked { onTap() }
        }

        override fun act(delta: Float) {
            super.act(delta)
            name = if (running) "停止" else "发送"
            bgMix.to(if (!running && active) 1f else 0f).update(delta)
            fgMix.to(if (active) 1f else 0f).update(delta)
            arrowA.to(if (running) 0f else 1f).update(delta)
            arrowS.to(if (running) 0.5f else 1f).update(delta)
            stopA.to(if (running) 1f else 0f).update(delta)
            stopS.to(if (running) 1f else 0.5f).update(delta)
        }

        override fun draw() {
            val a = parentAlpha * color.a
            val cx = x + width / 2f
            val cy = y + height / 2f
            Shapes.circle(cx, cy, width / 2f, Color(K.surface3).lerp(K.accent, bgMix.get()).a(a))
            val s = arrowS.get()
            arrow.tint.set(Color(K.dim).lerp(Color.white, fgMix.get())).a(arrowA.get() * a)
            val aw = arrow.prefWidth * s
            arrow.setBounds(cx - aw / 2f, cy - aw / 2f, aw, aw)
            if (arrowA.get() > 0.001f) arrow.draw()
            val sa = stopA.get()
            if (sa > 0.001f) {
                val side = dp(13f) * stopS.get()
                Shapes.rect(cx - side / 2f, cy - side / 2f, side, side, dp(2f) * stopS.get(), null, dp(2f) * stopS.get(), Color(K.text).a(sa * a))
            }
        }
    }
}

/** 键盘回车：桌面 / 物理键盘时 Shift+Enter 不发送。 */
internal fun isSubmitKey(c: Char) = (c == '\n' || c == '\r') && !Core.input.keyDown(KeyCode.shiftLeft)
