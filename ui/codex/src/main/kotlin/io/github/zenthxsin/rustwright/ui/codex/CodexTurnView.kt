package io.github.zenthxsin.rustwright.ui.codex

import arc.graphics.Color
import arc.scene.event.Touchable
import arc.scene.ui.Label
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import arc.util.Time
import io.github.zenthxsin.rustwright.core.codex.CodexItem
import io.github.zenthxsin.rustwright.core.codex.CodexTurn
import io.github.zenthxsin.rustwright.ui.designsystem.Lucide
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp

/**
 * 一轮任务（conversation.tsx 的 CodexTurn）：
 * 用户消息 + 操作按钮；助手部分 = 计划 / 执行过程（可展开的时间线）/ 最终回复 / 修改的文件 + 操作按钮；停止 / 失败提示。
 */
internal class CodexTurnView(
    private val turn: CodexTurn,
    private val running: Boolean,
    private val width: () -> Float,
    private val state: UiState,
    private val callbacks: Actions,
) : Table() {

    /** 展开状态跨重建保留。 */
    class UiState {
        val expanded = HashSet<String>()
        val collapsed = HashSet<String>()
    }

    interface Actions {
        fun copy(text: String)
        fun edit(turn: CodexTurn, text: String)
        fun retry(turn: CodexTurn, text: String)
        fun delete(turn: CodexTurn, role: String)
        fun changed()
    }

    init {
        top().left()
        val users = turn.items.filter { it.type == "userMessage" }
        val prompt = users.joinToString("\n") { it.text }
        val agents = turn.items.filter { it.type == "agentMessage" }
        val final = if (running) null else agents.lastOrNull()
        val activity = turn.items.filter { it.type != "userMessage" && it !== final }

        // section.space-y-3
        var first = true
        fun gap() = if (first) 0f.also { first = false } else dp(12f)

        if ("user" !in turn.deletedRoles) users.forEach { u ->
            add(userMessage(u.text)).growX().padTop(gap()).row()
            add(messageActions(user = true, content = u.text, prompt = prompt)).growX().padTop(dp(4f)).row()
        }
        val hasAssistant = final != null || activity.isNotEmpty() || turn.plan.isNotEmpty() || turn.status == "failed" || turn.status == "interrupted"
        if ("assistant" !in turn.deletedRoles && hasAssistant) {
            // .codex-task-response-body.space-y-2
            val body = Table().top().left()
            var inner = true
            fun innerGap() = if (inner) 0f.also { inner = false } else dp(8f)
            if (turn.plan.isNotEmpty()) body.add(plan()).growX().padTop(innerGap()).row()
            if (activity.isNotEmpty()) body.add(executionSteps(activity)).growX().padTop(innerGap()).row()
            final?.let { body.add(CodexMarkdown(callbacks::copy).apply { setMarkdown(it.text) }).growX().padTop(innerGap()).row() }
            changedFiles()?.let { body.add(it).growX().padTop(innerGap()).row() }
            add(body).growX().padTop(gap()).row()
            if (!running) add(messageActions(user = false, content = final?.text.orEmpty(), prompt = prompt)).growX().padTop(dp(4f)).row()
        }
        if ("assistant" !in turn.deletedRoles && turn.status == "interrupted") {
            add(Table().left().apply {
                add(Css.icon(Lucide.circlePause, 16f, Css.muted)).padRight(dp(8f))
                add(Css.para("任务已停止，发送消息即可继续。", 14f, Css.muted, lineHeight = 20f)).growX().minWidth(0f)
            }).growX().padTop(gap()).minHeight(dp(28f)).row()
        }
        if ("assistant" !in turn.deletedRoles && (turn.error != null || turn.status == "failed")) {
            val danger = RwTheme.Colors.danger
            add(Table(RwTheme.card(danger.cpy().a(0.05f), danger.cpy().a(0.2f), 12)).top().left().apply {
                margin(dp(12f))
                add(Css.icon(Lucide.circleAlert, 16f, danger)).top().padTop(dp(2f)).padRight(dp(8f))
                add(Table().top().left().apply {
                    add(Css.label("任务失败", 14f, danger, 500, 20f)).left().row()
                    turn.error?.let { add(Css.para(it, 14f, danger, lineHeight = 24f)).growX().padTop(dp(4f)) }
                }).growX().minWidth(0f)
            }).growX().padTop(gap()).row()
        }
    }

    /** .chatgpt-user-message：右对齐，最宽 90%，圆角 20，padding 12/16，#f1f1f1，15px / 1.65。 */
    private fun userMessage(text: String) = Table().right().apply {
        val maxW = width() * 0.9f
        val label = Css.para(text, 15f, Css.text, lineHeight = 24.75f)
        val font = RwTheme.text(15f, 400, 24.75f)
        val natural = text.lines().maxOf { Css.measure(font, it) }
        val w = minOf(maxW - dp(32f), natural + dp(14f))
        add(Table(RwTheme.round(20, RwTheme.Colors.userBubble)).apply {
            margin(dp(12f), dp(16f), dp(12f), dp(16f))
            add(label).width(w)
        })
    }

    /** 消息下方的操作（手机上常显）：复制 / 编辑 / 重试 / 删除，40px 按钮、18px 图标、次要色。 */
    private fun messageActions(user: Boolean, content: String, prompt: String) = Table().apply {
        if (user) right() else left()
        val row = Table()
        fun btn(glyph: Char, name: String, onClick: () -> Unit) {
            row.add(Css.iconButton(glyph, 18f, Css.muted, 12, name) { onClick() }).size(dp(40f)).padLeft(dp(2f))
        }
        var copied = 0f
        if (content.isNotBlank()) row.add(Css.ghost(12) { callbacks.copy(content); copied = Time.time }.apply {
            name = if (user) "复制消息" else "复制"
            val icon = Css.icon(Lucide.copy, 18f, Css.muted)
            add(icon)
            update { icon.setText((if (Time.time - copied < 120f && copied > 0f) Lucide.check else Lucide.copy).toString()) }
        }).size(dp(40f)).padLeft(dp(2f))
        if (user) btn(Lucide.pencil, "编辑消息") { callbacks.edit(turn, prompt) }
        if (prompt.isNotBlank()) btn(Lucide.rotateCcw, "重试") { callbacks.retry(turn, prompt) }
        btn(Lucide.trash, "删除") { callbacks.delete(turn, if (user) "user" else "assistant") }
        add(row).padLeft(if (user) 0f else -dp(8f)).padRight(if (user) -dp(4f) else 0f)
    }

    // region 执行过程

    /** ExecutionSteps：一行摘要（图标 / 执行过程 / 最近命令或文件 / N 个步骤 / 箭头），展开为左边线时间线。 */
    private fun executionSteps(items: List<CodexItem>): Table {
        val key = "steps:" + turn.id
        val latest = items.lastOrNull()
        val summary = when (latest?.type) {
            "commandExecution" -> latest.command
            "fileChange" -> latest.changes.firstOrNull()?.path?.substringAfterLast('/')
            else -> null
        }
        return disclosure(key, Lucide.listChecks, "执行过程", summary, hideSummaryWhenOpen = true,
            status = "${items.size} 个步骤", running = running, statusColor = Css.muted) {
            Table().top().left().apply {
                // .codex-execution-timeline：margin-left 7 + 左边线 1 + padding-left 12
                val list = Table().top().left()
                items.forEachIndexed { i, item -> list.add(itemView(item)).growX().padTop(if (i == 0) 0f else dp(2f)).row() }
                add(Css.rule()).width(dp(1f)).growY().padLeft(dp(7f))
                add(list).growX().padLeft(dp(12f)).padBottom(dp(2f))
            }
        }
    }

    /** ActivityDisclosure：min-height 28，gap 6，12px/20px 次要色；展开箭头旋转 90°。 */
    private fun disclosure(
        key: String, glyph: Char, label: String, summary: String?, hideSummaryWhenOpen: Boolean = false,
        status: String? = null, statusColor: Color = Css.muted, running: Boolean = false, mono: Boolean = false,
        content: () -> Table,
    ): Table = Table().top().left().apply {
        val open = key in state.expanded
        add(Css.ghost(6) {
            if (!state.expanded.add(key)) state.expanded.remove(key)
            callbacks.changed()
        }.apply {
            left()
            name = "disclosure:$label"
            margin(dp(2f), 0f, dp(2f), 0f)
            add(if (running) Spinner(16f) else Css.icon(glyph, 16f, Css.muted)).size(dp(16f)).padRight(dp(6f))
            add(Css.label(label, 12f, Css.muted, lineHeight = 20f)).padRight(dp(6f))
            if (summary != null && !(open && hideSummaryWhenOpen)) {
                // 摘要按剩余宽度截断（min-w-0 truncate）
                val font = if (mono) RwTheme.monoAt(12f) else RwTheme.text(12f, 400, 20f)
                val fixed = Css.measure(RwTheme.text(12f, 400, 20f), label + (status ?: "")) + dp(16f + 14f + 6f * 4)
                val text = Css.fit(summary.replace('\n', ' '), font, (width() - fixed).coerceAtLeast(dp(40f)))
                add(Label(text, Label.LabelStyle(font, Css.muted))).padRight(dp(6f))
            }
            status?.let { add(Css.label(it, 12f, statusColor, lineHeight = 16f)).padRight(dp(6f)) }
            add(Css.icon(if (open) Lucide.chevronDown else Lucide.chevronRight, 14f, Css.muted))
        }).left().minHeight(dp(28f)).row()
        if (open) add(content()).growX().padTop(dp(4f)).row()
    }

    private fun stateOf(item: CodexItem): String? = when {
        item.status == "failed" || item.status == "declined" || (item.exitCode != null && item.exitCode != 0) -> "failed"
        item.status == "interrupted" || item.status == "stopped" || item.status == "cancelled" -> "stopped"
        item.status == "completed" || item.exitCode == 0 -> "completed"
        item.status == "inProgress" -> if (running) "running" else if (turn.status == "failed") "failed" else "stopped"
        item.status == "pending" -> "pending"
        else -> null
    }

    private fun statusLabel(state: String?, exitCode: Int? = null): String? {
        var label = when (state) { "running" -> "运行中"; "completed" -> "已完成"; "failed" -> "失败"; "stopped" -> "已停止"; "pending" -> "待确认"; else -> null }
        if (exitCode != null && state != "running") label = "退出码 $exitCode"
        return label
    }

    private fun itemView(item: CodexItem): Table {
        val st = stateOf(item)
        val stColor = if (st == "failed") RwTheme.Colors.danger else Css.muted
        val key = "item:" + item.id
        return when (item.type) {
            "reasoning" -> disclosure(key, Lucide.brain, "推理摘要", null, running = st == "running") {
                Table().left().apply { add(Css.para(item.text.ifBlank { "思考中..." }, 14f, Css.muted, lineHeight = 28f)).growX() }
            }
            "agentMessage" -> Table().left().apply { add(CodexMarkdown(callbacks::copy).apply { setMarkdown(item.text) }).growX() }
            "commandExecution" -> disclosure(key, Lucide.terminal, "命令", item.command?.let { CodexScreen.shortCommand(it) } ?: "命令",
                status = statusLabel(st, item.exitCode), statusColor = stColor, running = st == "running", mono = true) {
                Table(RwTheme.card(Css.hover.cpy().a(0.4f), Css.border, 12)).top().left().apply {
                    item.cwd?.let {
                        add(Label(it, Label.LabelStyle(RwTheme.monoAt(12f), Css.muted)).apply { setWrap(true) }).growX().pad(dp(8f), dp(12f), dp(8f), dp(12f)).row()
                        add(Css.rule()).growX().height(dp(1f)).row()
                    }
                    add(pre(item.output.ifBlank { "命令没有输出。" }.lines().takeLast(200).joinToString("\n"))).growX().maxHeight(dp(320f)).pad(dp(12f))
                }
            }
            "fileChange" -> {
                val label = if (st in setOf("failed", "running", "pending", "stopped")) "文件改动" else "修改了 ${item.changes.size} 个文件"
                disclosure(key, Lucide.fileCode, label, item.changes.joinToString(", ") { it.path.substringAfterLast('/') },
                    status = statusLabel(st), statusColor = stColor, running = st == "running") {
                    Table(RwTheme.card(Css.hover.cpy().a(0.3f), Css.border, 12)).top().left().apply {
                        item.changes.forEachIndexed { i, ch ->
                            if (i > 0) add(Css.rule()).growX().height(dp(1f)).row()
                            add(Label(ch.path, Label.LabelStyle(RwTheme.monoAt(12f), Css.text)).apply { setWrap(true) }).growX().pad(dp(8f), dp(12f), dp(8f), dp(12f)).row()
                            if (ch.diff.isNotBlank()) add(diff(ch.diff)).growX().maxHeight(dp(288f)).pad(0f, dp(12f), dp(12f), dp(12f)).row()
                        }
                    }
                }
            }
            "contextCompaction" -> Table().left().apply { add(Css.label("对话上下文已压缩。", 12f, Css.muted)).padTop(dp(8f)).padBottom(dp(8f)) }
            else -> {
                val label = if (item.type == "webSearch") "网络搜索" else "工具执行记录"
                val summary = item.tool ?: item.text.takeIf { item.type == "webSearch" }
                disclosure(key, Lucide.globe, label, summary, hideSummaryWhenOpen = true, status = statusLabel(st), statusColor = stColor, running = st == "running") {
                    Table().top().left().apply {
                        item.tool?.let { add(Label(listOfNotNull(item.server, it).joinToString(" · "), Label.LabelStyle(RwTheme.monoAt(12f), Css.muted))).left().row() }
                        item.arguments?.takeIf { it.isNotBlank() }?.let { add(pre(it.take(2000))).growX().padTop(dp(12f)).row() }
                        val out = item.error ?: item.result
                        if (out != null) add(Css.para(out.take(4000), 14f, if (item.error != null) RwTheme.Colors.danger else Css.text, lineHeight = 24f)).growX().padTop(dp(12f)).row()
                        else add(Css.para(if (st == "running") "正在等待输出…" else "没有返回输出。", 14f, Css.muted, lineHeight = 24f)).growX().padTop(dp(12f)).row()
                    }
                }
            }
        }
    }

    private fun pre(text: String) = ScrollPane(Label(text, Label.LabelStyle(RwTheme.monoAt(12f, 20f), Css.text))).apply {
        setScrollingDisabled(false, false); setFadeScrollBars(true); setOverscroll(false, false)
    }

    private fun diff(text: String) = ScrollPane(Table().top().left().apply {
        text.lines().take(400).forEach { line ->
            val color = when {
                line.startsWith("+") && !line.startsWith("+++") -> Css.green
                line.startsWith("-") && !line.startsWith("---") -> RwTheme.Colors.diffRemoved
                else -> Css.text
            }
            add(Label(line.ifEmpty { " " }, Label.LabelStyle(RwTheme.monoAt(12f, 20f), color))).left().row()
        }
    }).apply { setScrollingDisabled(false, false); setFadeScrollBars(true); setOverscroll(false, false) }

    // endregion

    /** 计划（details.codex-plan）：标题 + 完成数，每步一行状态图标。 */
    private fun plan() = Table().top().left().apply {
        val done = turn.plan.count { it.status == "completed" }
        val key = "plan:" + turn.id
        add(disclosure(key, Lucide.listTodo, "实施计划", null, status = "$done/${turn.plan.size}", running = false) {
            Table().top().left().apply {
                turn.plan.forEach { step ->
                    val (glyph, color) = when (step.status) {
                        "completed" -> Lucide.circleCheck to Css.green
                        "inProgress" -> Lucide.loader to RwTheme.Colors.blue
                        else -> Lucide.circle to Css.muted
                    }
                    add(Css.icon(glyph, 14f, color)).top().padTop(dp(3f)).padRight(dp(8f))
                    add(Css.para(step.step, 13f, Css.text, lineHeight = 21f)).growX().minWidth(0f).padBottom(dp(4f)).row()
                }
            }
        }.also { if (running && key !in state.collapsed) state.expanded.add(key) }).growX()
    }

    /** TaskChangedFiles：默认展开；标题 14px 500 + 箭头，文件行 min-h 36、12px 路径、+N / −N、点开看 diff。 */
    private fun changedFiles(): Table? {
        val changes = LinkedHashMap<String, io.github.zenthxsin.rustwright.core.codex.CodexFileChange>()
        turn.items.filter { it.type == "fileChange" && (it.status == null || it.status == "completed") }.forEach { it.changes.forEach { c -> changes[c.path] = c } }
        if (changes.isEmpty()) return null
        val key = "changed:" + turn.id
        val open = key !in state.collapsed
        return Table().top().left().apply {
            add(Css.ghost(8) {
                if (!state.collapsed.add(key)) state.collapsed.remove(key)
                callbacks.changed()
            }.apply {
                margin(0f, dp(8f), 0f, dp(8f))
                add(Css.label("修改了 ${changes.size} 个文件", 14f, Css.text, 500, 20f)).padRight(dp(8f))
                add(Css.icon(if (open) Lucide.chevronDown else Lucide.chevronRight, 14f, Css.text))
            }).left().minHeight(dp(36f)).row()
            if (open) changes.values.forEach { ch ->
                val k = "file:" + turn.id + ch.path
                val fileOpen = k in state.expanded
                add(Css.ghost(8) {
                    if (!state.expanded.add(k)) state.expanded.remove(k)
                    callbacks.changed()
                }.apply {
                    left()
                    margin(0f, dp(8f), 0f, dp(8f))
                    add(Css.icon(Lucide.fileCode, 16f, Css.text)).padRight(dp(8f))
                    add(Css.label(ch.path, 12f, Css.text, lineHeight = 16f).apply { setEllipsis(true) }).growX().minWidth(0f)
                    if (ch.added > 0) add(Css.label("+${ch.added}", 12f, Css.green, lineHeight = 16f)).padLeft(dp(8f))
                    if (ch.removed > 0) add(Css.label("-${ch.removed}", 12f, RwTheme.Colors.diffRemoved, lineHeight = 16f)).padLeft(dp(8f))
                    if (ch.diff.isNotBlank()) add(Css.icon(if (fileOpen) Lucide.chevronDown else Lucide.chevronRight, 14f, Css.text)).padLeft(dp(8f))
                }).growX().minHeight(dp(36f)).padTop(dp(4f)).row()
                if (fileOpen && ch.diff.isNotBlank()) add(Table(RwTheme.round(12, Css.hover.cpy().a(0.5f))).apply {
                    margin(dp(12f)); add(diff(ch.diff)).growX().maxHeight(dp(288f))
                }).growX().padTop(dp(4f)).row()
            }
        }
    }
}

/** 旋转的 loader-circle（animate-spin）：3/4 圆弧，线宽按 Lucide 的 2/24。 */
internal class Spinner(private val px: Float) : arc.scene.Element() {
    override fun draw() {
        val r = dp(px) * 10f / 24f
        arc.graphics.g2d.Draw.color(Css.muted, parentAlpha)
        arc.graphics.g2d.Lines.stroke(dp(px) * 2f / 24f)
        arc.graphics.g2d.Lines.arc(x + width / 2, y + height / 2, r, 0.75f, -(Time.globalTime * 6f) % 360f, 40)
        arc.graphics.g2d.Draw.reset()
    }
    override fun getPrefWidth() = dp(px)
    override fun getPrefHeight() = dp(px)
    init { touchable = Touchable.disabled }
}
