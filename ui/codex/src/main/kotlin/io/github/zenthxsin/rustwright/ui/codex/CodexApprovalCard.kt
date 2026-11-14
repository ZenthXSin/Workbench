package io.github.zenthxsin.rustwright.ui.codex

import arc.graphics.Color
import arc.scene.event.Touchable
import arc.scene.ui.Label
import arc.scene.ui.TextField
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.codex.CodexAnswer
import io.github.zenthxsin.rustwright.core.codex.CodexApproval
import io.github.zenthxsin.rustwright.core.codex.CodexFileChange
import io.github.zenthxsin.rustwright.ui.designsystem.Lucide
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import kotlinx.serialization.json.JsonObject

/**
 * 审批 / 提问卡片（approval-card.tsx）：
 * 圆角卡片 + 灰底页眉（32px 图标框、「需要确认」11px、标题 14px 500、说明 12px）；
 * 正文 16px 间距：原因、可展开的命令、工作目录、将修改的文件、请求详情；底部分隔线 + 拒绝 / 允许一次胶囊。
 */
internal class CodexApprovalCard(
    private val approval: CodexApproval,
    private val fileChanges: List<CodexFileChange>,
    private val onAnswer: (CodexAnswer) -> Unit,
) : Table() {

    private val open = HashSet<String>()
    private var busy = false

    init { rebuild() }

    private fun rebuild() {
        clearChildren()
        val input = approval.kind == CodexApproval.Kind.QUESTION
        background(RwTheme.card(Css.bg, Css.border, 16))
        top().left()
        val title = when (approval.kind) {
            CodexApproval.Kind.QUESTION -> "Codex 需要你的回答"
            CodexApproval.Kind.COMMAND -> "运行此命令？"
            CodexApproval.Kind.FILE_CHANGE -> "应用这些文件改动？"
            CodexApproval.Kind.PERMISSIONS -> "允许本轮访问？"
            CodexApproval.Kind.TOOL -> "使用 ${approval.params["tool"]?.toString()?.trim('"')}？"
            CodexApproval.Kind.OTHER -> "需要确认"
        }
        // header：border-b bg-muted/20 p-4 gap-3
        add(Table(RwTheme.solid(if (RwTheme.dark) Color(1f, 1f, 1f, 0.03f) else Color.valueOf("fafafa"))).top().left().apply {
            margin(dp(16f))
            add(Table(RwTheme.card(Css.bg, Css.border, 10)).apply {
                add(Css.icon(if (input) Lucide.circleHelp else Lucide.shieldCheck, 16f))
            }).size(dp(32f)).top().padTop(dp(2f)).padRight(dp(12f))
            add(Table().top().left().apply {
                if (!input) add(Css.label("需要确认", 11f, Css.muted, 500, 19.25f)).left().padBottom(dp(2f)).row()
                add(Css.para(title, 14f, Css.text, 500, 24f)).growX().left().row()
                add(Css.para(if (input) "回答以下问题以继续任务。" else "检查此操作，再决定是否允许 Codex 继续。", 12f, Css.muted, lineHeight = 20f)).growX().left().padTop(dp(2f))
            }).growX().minWidth(0f)
        }).growX().row()
        add(Css.rule()).growX().height(dp(1f)).row()
        add(if (input) questionBody() else approvalBody()).growX()
    }

    private fun approvalBody() = Table().top().left().apply {
        margin(dp(16f))
        var first = true
        fun gap() = if (first) 0f.also { first = false } else dp(16f)
        val reason = approval.reason ?: (approval.params["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        add(Table().top().left().apply {
            add(Css.label("请求批准的原因", 12f, Css.muted, 500, 16f)).left().row()
            add(Css.para(reason?.takeIf { it.isNotBlank() } ?: "此请求未提供原因。", 14f, Css.text, lineHeight = 24f)).growX().padTop(dp(4f))
        }).growX().padTop(gap()).row()
        approval.command?.let { cmd ->
            val key = "cmd"
            val isOpen = key in open
            add(Table(RwTheme.card(Css.hover.cpy().a(0.3f), Css.border, 12)).top().left().apply {
                add(Css.ghost(12) { toggle(key) }.apply {
                    left()
                    name = "将执行的命令"
                    margin(dp(8f), dp(12f), dp(8f), dp(12f))
                    add(Css.icon(if (isOpen) Lucide.chevronDown else Lucide.chevronRight, 14f, Css.muted)).padRight(dp(8f))
                    add(Css.label(if (approval.kind == CodexApproval.Kind.TOOL) "参数" else "将执行的命令", 12f, Css.text, lineHeight = 16f)).padRight(dp(8f))
                    if (!isOpen) add(Label(cmd.replace('\n', ' '), Label.LabelStyle(RwTheme.monoAt(12f), Css.muted)).apply { setEllipsis(true) }).growX().minWidth(0f)
                    else add().growX()
                }).growX().minHeight(dp(44f)).row()
                if (isOpen) {
                    add(Css.rule()).growX().height(dp(1f)).row()
                    add(Label(cmd, Label.LabelStyle(RwTheme.monoAt(12f, 24f), Css.text)).apply { setWrap(true) }).growX().pad(dp(12f)).row()
                }
            }).growX().padTop(gap()).row()
        }
        approval.cwd?.let {
            add(RichText(listOf(
                RichText.Run("工作目录: ", RwTheme.text(12f, 400, 20f), Css.muted),
                RichText.Run(it, RwTheme.monoAt(12f), Css.muted),
            ), 20f)).growX().padTop(gap()).row()
        }
        if (approval.kind == CodexApproval.Kind.FILE_CHANGE && fileChanges.isNotEmpty()) {
            add(Table().top().left().apply {
                add(Css.label("将修改的文件", 12f, Css.muted, 500, 16f)).left().padBottom(dp(8f)).row()
                fileChanges.forEach { ch ->
                    val key = "file:" + ch.path
                    val isOpen = key in open
                    add(Table(RwTheme.card(Css.bg, Css.border, 12)).top().left().apply {
                        add(Css.ghost(12) { toggle(key) }.apply {
                            left()
                            margin(dp(12f), dp(12f), dp(12f), dp(12f))
                            add(Css.icon(if (isOpen) Lucide.chevronDown else Lucide.chevronRight, 14f, Css.muted)).top().padRight(dp(8f))
                            add(Css.icon(Lucide.filePenLine, 14f, Css.muted)).top().padRight(dp(8f))
                            add(Label(ch.path, Label.LabelStyle(RwTheme.monoAt(12f, 20f), Css.text)).apply { setWrap(true) }).growX().minWidth(0f)
                            add(Css.label(when (ch.kind) { "add" -> "已添加"; "delete" -> "已删除"; else -> "已修改" }, 12f, Css.muted, lineHeight = 20f)).padLeft(dp(8f)).top()
                        }).growX().row()
                        if (isOpen) {
                            add(Css.rule()).growX().height(dp(1f)).row()
                            add(Label(ch.diff.ifBlank { "这个文件没有改动预览。" }, Label.LabelStyle(RwTheme.monoAt(12f, 24f), Css.text)).apply { setWrap(true) }).growX().pad(dp(12f)).row()
                        }
                    }).growX().padBottom(dp(8f)).row()
                }
            }).growX().padTop(gap()).row()
        }
        val key = "details"
        val isOpen = key in open
        add(Table().top().left().apply {
            add(Css.ghost(6) { toggle(key) }.apply {
                left()
                add(Css.icon(if (isOpen) Lucide.chevronDown else Lucide.chevronRight, 14f, Css.muted)).padRight(dp(6f))
                add(Css.label("请求详情", 12f, Css.muted, lineHeight = 16f))
            }).left().minHeight(dp(36f)).row()
            if (isOpen) add(Table(RwTheme.round(12, Css.hover.cpy().a(0.4f))).apply {
                margin(dp(12f))
                add(Label(pretty(approval.params), Label.LabelStyle(RwTheme.monoAt(12f, 20f), Css.text)).apply { setWrap(true) }).growX()
            }).growX().padTop(dp(4f)).row()
        }).growX().padTop(gap()).row()
        // footer：border-t pt-4，右对齐
        add(Css.rule()).growX().height(dp(1f)).padTop(gap()).row()
        add(Table().right().apply {
            if (busy) add(Table().left().apply {
                add(Spinner(14f)).padRight(dp(6f))
                add(Css.label("提交中...", 12f, Css.muted))
            }).growX().left()
            add(Css.pill("拒绝", primary = false) { answer(CodexAnswer.Decline) }).height(dp(36f))
            add(Css.pill("允许一次", primary = true) { answer(CodexAnswer.Accept) }).height(dp(36f)).padLeft(dp(8f))
        }).growX().padTop(dp(16f))
    }

    private fun questionBody() = Table().top().left().apply {
        margin(dp(16f))
        val qs = approval.questions
        val selected = HashMap<String, MutableList<String>>()
        val texts = HashMap<String, TextField>()
        if (qs.isEmpty()) add(Css.para("此请求没有包含问题。", 14f, Css.muted)).growX().row()
        qs.forEachIndexed { i, q ->
            val box = Table().top().left()
            q.header?.let { box.add(Css.label(it, 12f, Css.muted)).left().padBottom(dp(4f)).row() }
            box.add(Css.para(q.question, 14f, Css.text, 500, 24f)).growX().padBottom(dp(8f)).row()
            if (q.multiSelect) box.add(Css.label("选择所有适用项", 12f, Css.muted)).left().padBottom(dp(8f)).row()
            val chosen = selected.getOrPut(q.id) { mutableListOf() }
            q.options.forEach { opt ->
                box.add(Table().left().apply {
                    touchable = Touchable.enabled
                    name = "option:$opt"
                    margin(dp(10f), dp(12f), dp(10f), dp(12f))
                    val mark = Css.icon(Lucide.circle, 16f, Css.muted)
                    add(mark).top().padRight(dp(12f))
                    add(Css.para(opt, 14f, Css.text, lineHeight = 20f)).growX().minWidth(0f)
                    val on = RwTheme.card(Css.hover.cpy().a(0.6f), Css.text.cpy().a(0.35f), 12)
                    val off = RwTheme.card(Css.bg, Css.border, 12)
                    update {
                        val c = opt in chosen
                        background(if (c) on else off)
                        mark.setText((if (c) (if (q.multiSelect) Lucide.circleCheck else Lucide.circleDot) else Lucide.circle).toString())
                        mark.style.fontColor = if (c) Css.text else Css.muted
                    }
                    clicked {
                        if (opt in chosen) chosen.remove(opt) else { if (!q.multiSelect) chosen.clear(); chosen += opt }
                    }
                }).growX().minHeight(dp(44f)).padBottom(dp(8f)).row()
            }
            if (q.allowOther || q.options.isEmpty()) {
                box.add(Css.label(if (q.options.isNotEmpty()) "或自行填写答案" else "你的答案", 12f, Css.muted)).left().padTop(dp(4f)).padBottom(dp(8f)).row()
                val field = TextField("", TextField.TextFieldStyle().apply {
                    font = RwTheme.text(16f)
                    fontColor = Css.text
                    messageFontColor = Css.muted
                    background = RwTheme.card(Css.bg, Css.border, 12).also { it.leftWidth = dp(12f); it.rightWidth = dp(12f); it.topHeight = dp(10f); it.bottomHeight = dp(10f) }
                    focusedBackground = RwTheme.card(Css.bg, RwTheme.Colors.outlineStrong, 12).also { it.leftWidth = dp(12f); it.rightWidth = dp(12f); it.topHeight = dp(10f); it.bottomHeight = dp(10f) }
                    cursor = RwTheme.solid(Css.text).also { it.minWidth = dp(2f) }
                    selection = RwTheme.solid(RwTheme.Colors.info.cpy().a(0.3f))
                }).apply { setMessageText("填写回答"); if (q.secret) setPasswordMode(true) }
                texts[q.id] = field
                box.add(field).growX().height(dp(44f)).row()
            }
            add(box).growX().padTop(if (i == 0) 0f else dp(20f)).row()
        }
        add(Css.rule()).growX().height(dp(1f)).padTop(dp(20f)).row()
        add(Table().apply {
            val progress = Css.label("", 12f, Css.muted)
            add(progress).growX().left()
            add(Css.pill("提交回答", primary = true) {
                val result = qs.associate { q -> q.id to (selected[q.id].orEmpty() + listOfNotNull(texts[q.id]?.text?.trim()?.takeIf { it.isNotEmpty() })) }
                if (result.values.any { it.isEmpty() } && qs.isNotEmpty()) return@pill
                answer(CodexAnswer.Answers(result))
            }).height(dp(36f))
            update {
                val answered = qs.count { q -> selected[q.id].orEmpty().isNotEmpty() || !texts[q.id]?.text.isNullOrBlank() }
                progress.setText(if (busy) "提交中..." else "已回答 $answered / ${qs.size} 个问题")
            }
        }).growX().padTop(dp(16f))
    }

    private fun toggle(key: String) { if (!open.add(key)) open.remove(key); rebuild() }

    private fun answer(a: CodexAnswer) {
        if (busy) return
        busy = true
        rebuild()
        onAnswer(a)
    }

    private fun pretty(o: JsonObject) = kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), o)

}

/**
 * 待执行消息（queued-turns.tsx）：圆角卡片，标题「待执行消息（N）」可折叠，每条显示文字、排队状态和取消按钮。
 */
internal class CodexQueuedCard(
    private val items: List<io.github.zenthxsin.rustwright.core.codex.CodexQueued>,
    private val onCancel: (String) -> Unit,
) : Table() {
    private var open = true

    init { rebuild() }

    private fun rebuild() {
        clearChildren()
        background(RwTheme.card(Css.bg, Css.border.cpy().a(0.7f), 16))
        top().left()
        add(Css.ghost(16) { open = !open; rebuild() }.apply {
            left()
            margin(dp(12f), dp(16f), dp(12f), dp(16f))
            add(Css.icon(Lucide.clock, 16f, Css.muted)).padRight(dp(10f))
            add(Css.label("待执行消息（${items.size}）", 12f, Css.muted, 500, 16f)).growX().left()
            add(Css.icon(if (open) Lucide.chevronUp else Lucide.chevronDown, 16f, Css.muted))
        }).growX().row()
        if (!open) return
        items.forEach { q ->
            add(Table().left().apply {
                margin(0f, dp(16f), dp(12f), dp(12f))
                add(Table().top().left().apply {
                    add(Css.para(q.text, 14f, Css.text, lineHeight = 20f)).growX().left().row()
                    add(Table().left().apply {
                        add(Css.icon(Lucide.clock, 12f, Css.muted)).padRight(dp(6f))
                        add(Css.label("排队中", 12f, Css.muted, lineHeight = 16f))
                    }).left().padTop(dp(4f))
                }).growX().minWidth(0f)
                add(Css.iconButton(Lucide.x, 16f, Css.text, 999, "取消待执行消息") { onCancel(q.id) }).size(dp(36f)).padLeft(dp(8f))
            }).growX().row()
        }
    }
}
