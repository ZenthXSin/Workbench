package io.github.zenthxsin.rustwright.ui.codex

import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.input.KeyCode
import arc.scene.Element
import arc.scene.event.InputEvent
import arc.scene.event.InputListener
import arc.scene.event.Touchable
import arc.scene.ui.TextArea
import arc.scene.ui.TextField
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.codex.CodexConversation
import io.github.zenthxsin.rustwright.core.codex.CodexMode
import io.github.zenthxsin.rustwright.core.codex.CodexModel
import io.github.zenthxsin.rustwright.core.codex.CodexWorkbench
import io.github.zenthxsin.rustwright.ui.designsystem.Lucide
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.widgets.Popover

/** 一个附件：Linux 内路径 + 显示名；图片以 localImage 交给 Codex。 */
data class CodexAttachment(val path: String, val name: String, val image: Boolean)

/**
 * 输入框（composer.tsx + chatgpt.css，手机尺寸）：
 * form 圆角 20、1px #e5e5e5、padding 8；输入 16px，min-height 48，padding 8 4 12；
 * 工具栏 min-height 44：左「+」（新建页 44 / 会话页 40）与审批模式胶囊（高 36、13px、盾牌 14）；
 * 右「模型 · 强度」胶囊（高 28、12px、#f1f1f1）、引导 / 并行 / 打断并发送（44）、停止（蓝色 32）、发送（44）。
 */
class CodexComposer(
    private val wb: CodexWorkbench,
    private val conversation: () -> CodexConversation?,
    private val onSubmit: (text: String, action: Action, attachments: List<CodexAttachment>) -> Boolean,
    private val onStop: () -> Unit,
    private val onAttach: (add: (CodexAttachment) -> Unit) -> Unit,
) : Table() {

    enum class Action { SEND, STEER, INTERRUPT }

    /** 会话页用紧凑尺寸（加号 40）。 */
    var compact = false
        set(value) { if (field != value) { field = value; plusCell?.size(dp(if (value) 40f else 44f)); invalidateHierarchy() } }

    /** 还没有会话时选的模式 / 模型 / 强度，新建会话时使用。 */
    var pendingMode = CodexMode.CODE
    var pendingModel: String? = null
    var pendingEffort: String? = null

    val input = TextArea("", TextField.TextFieldStyle().apply {
        font = RwTheme.text(16f, 400, 26f)
        fontColor = Css.text
        messageFontColor = Css.muted
        cursor = RwTheme.solid(Css.text).also { it.minWidth = dp(1.5f) }
        selection = RwTheme.solid(RwTheme.Colors.info.cpy().a(0.3f))
    }).apply { setMessageText("与 Codex 协作") }

    private val attachments = ArrayList<CodexAttachment>()
    private val chips = Table().left()
    private var plusCell: arc.scene.ui.layout.Cell<*>? = null

    private val mode: CodexMode get() = conversation()?.mode ?: pendingMode
    private val model: String get() = conversation()?.model ?: pendingModel ?: wb.defaultModel()
    private val effort: String get() = conversation()?.effort ?: pendingEffort ?: currentModel()?.defaultEffort ?: "medium"
    private val running: Boolean get() = conversation()?.status?.active == true
    private val hasDraft: Boolean get() = input.text.isNotBlank()

    init {
        val idle = RwTheme.card(RwTheme.Colors.input, Css.border, 20)
        val focused = RwTheme.card(RwTheme.Colors.input, Css.border.cpy().lerp(Color.valueOf(if (RwTheme.dark) "8e8e8e" else "808080"), 0.65f), 20)
        background(idle)
        margin(dp(8f))
        update { background(if (Core.scene.keyboardFocus === input) focused else idle) }
        add(chips).left().growX().row()
        add(inputRow()).growX().row()
        add(toolbar()).growX().minHeight(dp(44f))
    }

    /** .chatgpt-composer-input */
    private fun inputRow() = Table().apply {
        margin(dp(8f), dp(4f), dp(12f), dp(4f))
        val lh = dp(26f)
        var shown = -1
        val cell = add(input).growX().height(lh)
        update {
            val lines = if (input.text.isEmpty()) 1 else (input.text.count { it == '\n' } + 1).coerceAtLeast(input.lines).coerceAtLeast(1)
            if (lines != shown) {
                shown = lines
                cell.height((lh * lines).coerceAtMost(dp(204f)))
                invalidateHierarchy()
            }
        }
        input.setTextFieldListener { _, c -> if ((c == '\n' || c == '\r') && !Core.input.keyDown(KeyCode.shiftLeft)) submit(Action.SEND) }
    }

    private fun circleGhost(glyph: Char, px: Float, name: String, onClick: (Table) -> Unit) = Css.iconButton(glyph, px, Css.text, 999, name, onClick)

    private val steerButton by lazy { circleGhost(Lucide.messageSquarePlus, 16f, "引导当前任务") { submit(Action.STEER) } }
    private val interruptButton by lazy { circleGhost(Lucide.cornerUpLeft, 20f, "打断并发送") { submit(Action.INTERRUPT) } }
    private val stop by lazy {
        Table(RwTheme.round(16, Css.blueStop)).apply {
            name = "停止任务"
            touchable = Touchable.enabled
            // Square fill-current：14px 图标里 18/24 的实心圆角方块
            add(Table(RwTheme.round(2, Color.white))).size(dp(14f * 18f / 24f))
            clicked { onStop() }
        }
    }
    private val send by lazy {
        Table().apply {
            name = "发送"
            touchable = Touchable.enabled
            val on = RwTheme.round(22, RwTheme.Colors.accent)
            val off = RwTheme.round(22, Css.hover)
            val icon = Css.icon(Lucide.arrowUp, 16f, RwTheme.Colors.onAccent)
            add(icon)
            clicked { if (hasDraft || attachments.isNotEmpty()) submit(Action.SEND) }
            update {
                val enabled = hasDraft || attachments.isNotEmpty()
                background(if (enabled) on else off)
                icon.style.fontColor = if (enabled) RwTheme.Colors.onAccent else Css.subtle
            }
        }
    }

    private fun toolbar() = Table().apply {
        left()
        val controls = Table().left()
        plusCell = controls.add(circleGhost(Lucide.plus, 24f, "添加文件及更多内容") { anchor -> attachMenu(anchor) }).size(dp(44f))
        controls.add(permissionPill()).height(dp(36f)).padLeft(dp(2f)).maxWidth(dp(180f))
        add(controls).growX().minWidth(0f).left()
        add(reasoningPill()).height(dp(28f)).minWidth(0f).maxWidth(dp(200f))
        val actions = Table()
        add(actions)
        var key = ""
        update {
            val steer = running && hasDraft && conversation()?.activeTurnId != null
            val k = "$running/$hasDraft/$steer"
            if (k == key) return@update
            key = k
            actions.clearChildren()
            if (steer) actions.add(steerButton).size(dp(44f)).padLeft(dp(6f))
            if (running && hasDraft) actions.add(interruptButton).size(dp(44f)).padLeft(dp(6f))
            if (running) actions.add(stop).size(dp(32f)).padLeft(dp(6f))
            if (!running || hasDraft) actions.add(send).size(dp(44f)).padLeft(dp(6f))
            invalidateHierarchy()
        }
    }

    /** 审批模式：ShieldCheck 14 + 13px 文字，padding 0 4，无下拉箭头；完全访问橙色。 */
    private fun permissionPill() = Css.ghost(999) { anchor ->
        if (running) return@ghost
        val modes = listOf(CodexMode.CODE, CodexMode.AUTO, CodexMode.FULL, CodexMode.ASK)
        Popover.menu(anchor, modes.map { Popover.Option(modeLabel(it), modeDescription(it), it == mode) }) { i -> setMode(modes[i]) }
    }.apply {
        name = "审批模式"
        margin(0f, dp(4f), 0f, dp(4f))
        val icon = Css.icon(Lucide.shieldCheck, 14f)
        val text = Css.label("", 13f, Css.text, lineHeight = 20f)
        add(icon).padRight(dp(4f))
        add(text)
        update {
            val tint = if (mode == CodexMode.FULL) RwTheme.Colors.orange else Css.text
            icon.style.fontColor = tint; text.style.fontColor = tint
            val t = modeLabel(mode)
            if (text.text.toString() != t) { text.setText(t); invalidateHierarchy() }
            color.a = if (running) 0.5f else 1f
        }
    }

    /** 「模型 · 强度 ⌄」：rounded-full px-3（.codex-reasoning-trigger 改为 9px）、高 28、#f1f1f1、12px #666。 */
    private fun reasoningPill() = Table(RwTheme.round(14, RwTheme.Colors.userBubble)).apply {
        name = "推理强度"
        touchable = Touchable.enabled
        margin(0f, dp(9f), 0f, dp(9f))
        val name = Css.label("", 12f, Css.muted, lineHeight = 20f)
        val level = Css.label("", 12f, Css.muted, lineHeight = 20f)
        add(name)
        add(Css.label("·", 12f, Css.muted.cpy().a(0.4f))).padLeft(dp(6f)).padRight(dp(6f))
        add(level)
        add(Css.icon(Lucide.chevronDown, 14f, Css.muted)).padLeft(dp(6f))
        clicked { if (!running) reasoningPopover(this) }
        update {
            // 网页按宽度截断模型名（truncate），手机上通常只剩「GPT-…」
            val full = modelName(model)
            val m = if (full.length > 6) full.take(4) + "..." else full
            val e = effortLabel(effort)
            if (name.text.toString() != m || level.text.toString() != e) { name.setText(m); level.setText(e); invalidateHierarchy() }
            color.a = if (running) 0.5f else 1f
        }
    }

    /** 「+」菜单：添加照片和文件（paperclip 20，h44，圆角 8）。 */
    private fun attachMenu(anchor: Element) {
        var close: () -> Unit = {}
        val list = Table().apply {
            margin(dp(6f))
            add(Css.ghost(8) { close(); onAttach { a -> attachments += a; rebuildChips() } }.apply {
                left()
                name = "添加照片和文件"
                margin(dp(10f), dp(12f), dp(10f), dp(12f))
                add(Css.icon(Lucide.paperclip, 20f)).padRight(dp(12f))
                add(Css.label("添加照片和文件", 14f, Css.text, lineHeight = 20f)).growX().left()
            }).growX().minHeight(dp(44f))
        }
        close = Popover.show(anchor, list, width = minOf(dp(256f), Core.scene.width - dp(24f)))
    }

    /** 附件：圆角 16 描边，回形针 + 文件名 + 关闭（36）。 */
    private fun rebuildChips() {
        chips.clearChildren()
        if (attachments.isEmpty()) { invalidateHierarchy(); return }
        val row = Table().left()
        attachments.forEach { a ->
            row.add(Table(RwTheme.card(Css.bg, Css.border.cpy().a(0.7f), 16)).apply {
                margin(dp(4f), dp(12f), dp(4f), dp(4f))
                add(Css.icon(Lucide.paperclip, 16f, Css.muted)).padRight(dp(8f))
                add(Css.label(if (a.name.length > 24) a.name.take(23) + "…" else a.name, 14f))
                add(Css.iconButton(Lucide.x, 12f, Css.text, 999, "移除附件") { attachments.remove(a); rebuildChips() }).size(dp(36f)).padLeft(dp(2f))
            }).padRight(dp(8f)).padBottom(dp(8f))
        }
        chips.add(row).left().pad(dp(4f), dp(4f), 0f, dp(4f))
        invalidateHierarchy()
    }

    fun setDraft(text: String) {
        input.setText(text)
        input.setCursorPosition(text.length)
        Core.scene.setKeyboardFocus(input)
    }

    private fun submit(action: Action) {
        val text = input.text.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        if (onSubmit(text, action, attachments.toList())) {
            input.setText("")
            attachments.clear(); rebuildChips()
        }
    }

    private fun setMode(m: CodexMode) {
        val c = conversation()
        if (c != null) wb.setMode(c.id, m) else pendingMode = m
    }

    private fun setModel(id: String, e: String?) {
        val c = conversation()
        if (c != null) wb.setModel(c.id, id, e) else { pendingModel = id; pendingEffort = e }
    }

    private fun currentModel(): CodexModel? = wb.models.firstOrNull { it.id == model }

    private fun modelName(id: String) = wb.models.firstOrNull { it.id == id }?.name ?: id

    /**
     * 推理强度弹层（reasoning-control.tsx）：宽 256、圆角 14、padding 12；
     * 顶行 24px 三栏：闪电 14 #8f8f8f / 强度名 14px 500（按档位着色）+ 模型名 12px / 重置 24；下方 12px 间距后是 28 高滑杆。
     */
    private fun reasoningPopover(anchor: Element) {
        val m = currentModel()
        val efforts = m?.efforts?.ifEmpty { null } ?: listOf("low", "medium", "high", "xhigh")
        var index = efforts.indexOf(effort).coerceAtLeast(0)
        var close: () -> Unit = {}
        val gray = Color.valueOf("8f8f8f")
        val title = Css.label("", 14f, effortColor(efforts[index]), 500, 20f)
        val content = Table().top().apply {
            margin(dp(12f))
            add(Table().apply {
                add(Css.icon(Lucide.zap, 14f, gray)).size(dp(24f)).top()
                add(Table().apply {
                    add(title).row()
                    add(Css.ghost(6) { close(); modelMenu(anchor) }.apply {
                        add(Css.label(modelName(model), 12f, Css.muted, lineHeight = 20f))
                        add(Css.icon(Lucide.chevronDown, 16f, Css.muted)).padLeft(dp(4f))
                    }).padTop(dp(2f))
                }).growX().minWidth(0f).padLeft(dp(8f)).padRight(dp(8f))
                add(Css.iconButton(Lucide.rotateCcw, 14f, gray, 999, "重置推理强度") {
                    m?.defaultEffort?.let { d -> index = efforts.indexOf(d).coerceAtLeast(0); setModel(model, d) }
                }).size(dp(24f)).top()
            }).growX().row()
            add(EffortSlider(efforts, { index }) { i -> index = i; setModel(model, efforts[i]) }).growX().height(dp(28f)).padTop(dp(12f))
            update {
                val e = efforts[index]
                if (title.text.toString() != effortLabel(e)) title.setText(effortLabel(e))
                title.style.fontColor = effortColor(e)
            }
        }
        close = Popover.show(anchor, content, above = true, alignEnd = true, width = minOf(dp(256f), Core.scene.width - dp(24f)))
    }

    private fun modelMenu(anchor: Element) {
        val models = wb.models.ifEmpty { listOf(CodexModel(model, model, emptyList(), null)) }
        Popover.menu(anchor, models.map { Popover.Option(it.name, null, it.id == model) }, alignEnd = true) { i ->
            setModel(models[i].id, models[i].defaultEffort)
        }
    }

    /**
     * 滑杆：轨道 #e9e9eb 圆角满；填充 105° 渐变（档位色 → #6366f1）；
     * 拇指 26 白色、1px #f0f0f0 描边、轻阴影；每档一个 4px 点（已过的白 50%，未过的黑 20%）。
     */
    private class EffortSlider(val efforts: List<String>, val index: () -> Int, val onChange: (Int) -> Unit) : Element() {
        init {
            touchable = Touchable.enabled
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: KeyCode): Boolean { pick(x); return true }
                override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) = pick(x)
            })
        }

        private fun pick(x: Float) {
            val n = efforts.size
            if (n < 2) return
            val i = Math.round(((x - dp(13f)) / (width - dp(26f))) * (n - 1)).coerceIn(0, n - 1)
            if (i != index()) onChange(i)
        }

        override fun draw() {
            val n = efforts.size
            val i = index().coerceIn(0, maxOf(0, n - 1))
            val progress = if (n < 2) 0f else i / (n - 1f)
            val tw = dp(26f)
            val offset = (width - tw) * progress
            Draw.color(Color.white, parentAlpha)
            RwTheme.round(14, Css.selected).draw(x, y, width, height)
            // 渐变填充：逐列着色，两端圆角由圆形端帽补齐
            val from = effortColor(efforts.getOrNull(i) ?: "medium")
            val to = Color.valueOf("6366f1")
            val fillW = offset + tw
            val h = height - dp(4f)
            val y0 = y + dp(2f)
            val r = h / 2
            Draw.color(from, parentAlpha); Fill.poly(x + r, y0 + r, 40, r)
            Draw.color(Color(from).lerp(to, 1f), parentAlpha); Fill.poly(x + fillW - r, y0 + r, 40, r)
            val steps = 24
            val inner = (fillW - h).coerceAtLeast(0f)
            for (s in 0 until steps) {
                val c1 = Color(from).lerp(to, s / steps.toFloat())
                Draw.color(c1, parentAlpha)
                Fill.rect(x + r + inner * (s + 0.5f) / steps, y0 + r, inner / steps + 1f, h)
            }
            val dot = dp(4f)
            for (k in 0 until n) {
                val cx = x + dp(12f) + (width - dp(24f)) * (if (n < 2) 0f else k / (n - 1f))
                val dc = if (k <= i) Color(1f, 1f, 1f, 0.5f) else if (RwTheme.dark) Color(1f, 1f, 1f, 0.2f) else Color(0f, 0f, 0f, 0.2f)
                Draw.color(dc.r, dc.g, dc.b, dc.a * parentAlpha)
                Fill.poly(cx, y + height / 2, 40, dot / 2)
            }
            // 拇指：阴影 → 描边 → 白
            Draw.color(0f, 0f, 0f, 0.12f * parentAlpha); Fill.poly(x + offset + tw / 2, y + height / 2 - dp(1f), 40, tw / 2 + dp(1f))
            Draw.color(Color.valueOf("f0f0f0"), parentAlpha); Fill.poly(x + offset + tw / 2, y + height / 2, 40, tw / 2)
            Draw.color(Color.white, parentAlpha); Fill.poly(x + offset + tw / 2, y + height / 2, 40, tw / 2 - dp(1f))
            Draw.reset()
        }
    }

    companion object {
        fun modeLabel(m: CodexMode) = when (m) {
            CodexMode.CODE -> "请求批准"; CodexMode.AUTO -> "自动批准"; CodexMode.FULL -> "完全访问"; CodexMode.ASK -> "只读"
        }

        fun modeDescription(m: CodexMode) = when (m) {
            CodexMode.CODE -> "编辑文件并运行命令，需要批准时暂停"
            CodexMode.AUTO -> "自动批准执行环境中的命令和文件修改"
            CodexMode.FULL -> "无需审批即可使用整个执行环境"
            CodexMode.ASK -> "了解项目，不修改文件"
        }

        /** 强度名与网页一致，不随界面语言翻译。 */
        fun effortLabel(e: String) = when (e) {
            "low" -> "Low"; "medium" -> "Medium"; "high" -> "High"; "xhigh" -> "Extra high"; "max" -> "Max"; "ultra" -> "Ultra"; "minimal" -> "Minimal"
            else -> e
        }

        /** .codex-reasoning-label[data-effort] 的颜色。 */
        fun effortColor(e: String): Color = Color.valueOf(when (e) {
            "low", "minimal" -> "329bd5"; "high" -> "6366f1"; "xhigh" -> "8b5cf6"; "max" -> "b44ce4"; "ultra" -> "a06af5"; else -> "3b82f6"
        })
    }
}
