package io.github.zenthxsin.rustwright.ui.editor

import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.editor.api.EditorDiagnostic
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import io.github.zenthxsin.rustwright.ui.kit.SheetPage
import io.github.zenthxsin.rustwright.ui.kit.Tap


/**
 * 问题面板（原编辑器 IssuesPanel）：标题行带错误 / 警告数；按节分组（可折叠，超过 30 条默认全折叠），
 * 节内同一键 ≥2 条时再套一层键标题；每条：图标、消息、`行:列 · 键`、规则名。点一条跳到该位置，「复制」整条。
 */
object EditorIssuesSheet {
    fun show(ctx: ProjectContext, fileName: String, problems: List<EditorDiagnostic>, onJump: (line: Int, column: Int) -> Unit, onFix: (String) -> Unit) {
        val issues = problems
        val groups = IssueEnricher.groupBySection(issues)
        val collapsed = HashSet<String>()
        if (issues.size > 30) groups.forEach { collapsed += IssueEnricher.sectionGroupKey(it.sectionRaw) }

        val body = Table().top()
        body.margin(0f, dp(12f), dp(20f), dp(12f))
        val list = Table().top()

        fun build() {
            list.clearChildren()
            if (issues.isEmpty()) {
                list.add(Table().apply {
                    add(K.svg(PI.check, 18f, K.green, 2f)).padRight(dp(8f))
                    add(K.text("没有发现问题", 15f, K.muted))
                }).pad(dp(24f))
                return
            }
            for (g in groups) {
                val key = IssueEnricher.sectionGroupKey(g.sectionRaw)
                val open = key !in collapsed
                val worst = g.issues.minOf { IssueEnricher.severityRank(it.severity) }
                val head = Tap(null, 8f) { if (!collapsed.remove(key)) collapsed += key; build() }
                head.name = "节 " + IssueEnricher.sectionGroupTitle(g.sectionRaw)
                head.margin(dp(8f), dp(8f), dp(8f), dp(8f))
                head.add(K.svg(if (open) PI.chevronDown else PI.chevronRight, 14f, K.subtle, 2f)).padRight(dp(6f))
                head.add(K.mono(IssueEnricher.sectionGroupTitle(g.sectionRaw), 13f, K.text, 15f).apply { setEllipsis(true) }).growX().minWidth(0f).left()
                head.add(countChip(g.issues.size, worst))
                list.add(head).growX().padTop(dp(4f)).row()
                if (!open) continue
                for (kg in IssueEnricher.groupIssuesInSection(g.issues)) {
                    val wrap = IssueEnricher.shouldWrapKeyGroup(kg.key, kg.issues.size)
                    if (wrap) {
                        val kh = Table().left()
                        kh.add(K.mono(kg.key.orEmpty(), 12f, K.muted, 14f)).left().growX()
                        kh.add(countChip(kg.issues.size, kg.issues.minOf { IssueEnricher.severityRank(it.severity) }))
                        list.add(kh).growX().pad(dp(4f), dp(30f), dp(2f), dp(8f)).row()
                    }
                    kg.issues.forEach { list.add(row(ctx, it, hideKey = wrap, onJump)).growX().padLeft(if (wrap) dp(36f) else dp(22f)).row() }
                }
            }
        }
        build()

        val errors = issues.count { it.severity == "error" }
        val warns = issues.count { it.severity == "warn" }
        val summary = Table().left()
        summary.add(K.svg(PI.warn, 16f, if (errors > 0) EditorColors.ERROR else if (warns > 0) EditorColors.WARN else K.subtle, 2f)).padRight(dp(8f))
        summary.add(K.text("问题", 15f, K.text, 600)).padRight(dp(8f))
        if (errors > 0) summary.add(countChip(errors, 0)).padRight(dp(4f))
        if (warns > 0) summary.add(countChip(warns, 1))
        summary.add().growX()
        if (errors + warns > 0) {
            val fix = Tap(null, 8f) {
                ctx.closeSheet()
                onFix(issues.joinToString("\n") { "${it.line}:${it.column} [${it.severity}] ${it.message}" }.take(1500))
            }
            fix.name = "交给助手修"
            fix.border = K.inputBorder
            fix.margin(dp(6f), dp(10f), dp(6f), dp(10f))
            fix.add(K.text("交给助手修", 13f, K.text))
            summary.add(fix)
        }
        body.add(summary).growX().pad(0f, dp(8f), dp(8f), dp(8f)).row()
        body.add(list).growX()
        ctx.sheet(SheetPage("问题 · $fileName", ScrollPane(body).apply { setScrollingDisabledX(true) }, height = 600f))
    }

    private fun countChip(n: Int, rank: Int) = Table(K.fill(K.input, 6f)).apply {
        margin(dp(1f), dp(6f), dp(1f), dp(6f))
        add(K.text("$n", 11f, when (rank) { 0 -> EditorColors.ERROR; 1 -> EditorColors.WARN; else -> K.subtle }, 600, 11f))
    }

    private fun row(ctx: ProjectContext, issue: EditorDiagnostic, hideKey: Boolean, onJump: (Int, Int) -> Unit): Tap {
        val t = Tap(null, 8f) { ctx.closeSheet(); onJump(issue.line, issue.column) }
        t.name = issue.message
        t.margin(dp(7f), dp(8f), dp(7f), dp(8f))
        val (icon, color) = when (issue.severity) {
            "error" -> PI.close to EditorColors.ERROR
            "warn" -> PI.warn to EditorColors.WARN
            else -> PI.info to K.subtle
        }
        t.add(K.svg(icon, 14f, color, 2f)).top().padTop(dp(2f)).padRight(dp(8f))
        val c = Table().left()
        // 给人看的是改写过的说明；「复制」和「交给助手修」仍用检查器原文（对 AI 更精确）
        val shown = issue.displayMessage
        if (shown.isNotBlank()) c.add(K.para(shown, 13.5f, K.text, lh = 20f)).growX().left().row()
        val meta = buildString {
            if (issue.line > 0) append("${issue.line}:${issue.column}")
            val k = issue.key?.takeIf { it.isNotBlank() && !hideKey }
            if (k != null) { if (isNotEmpty()) append(" · "); append(k) }
            else if (issue.section != null && issue.line <= 0) append(issue.section)
        }
        if (meta.isNotBlank()) c.add(K.mono(meta, 11f, K.subtle, 13f).apply { setEllipsis(true) }).growX().minWidth(0f).left().padTop(dp(3f)).row()
        // 没有中文名的规则会原样返回 ALL_CAPS 规则码：那是给开发者看的，不显示
        issue.code.takeIf { it.isNotBlank() }?.let { issue.codeLabel }?.takeIf { it.isNotBlank() && !Regex("[A-Z0-9_]+").matches(it) }?.let {
            c.add(K.text(it, 11f, K.subtle, lh = 11f)).left().padTop(dp(3f))
        }
        t.add(c).growX().minWidth(0f)
        val copy = Tap(null, 8f) {
            arc.Core.app.clipboardText = buildString {
                issue.section?.let { append(IssueEnricher.sectionGroupTitle(it)).append('\n') }
                if (issue.line > 0) append("${issue.line}:${issue.column} ")
                issue.key?.let { append("键: ").append(it).append('\n') }
                append(issue.message)
            }
            ctx.notify("已复制")
        }
        copy.name = "复制问题"
        copy.add(K.svg(PI.copy, 14f, K.subtle, 1.8f))
        t.add(copy).size(dp(30f)).top()
        return t
    }
}

private object IssueEnricher {
    data class Group(val sectionRaw: String?, val issues: List<EditorDiagnostic>)
    data class KeyGroup(val key: String?, val issues: List<EditorDiagnostic>)
    fun groupBySection(issues: List<EditorDiagnostic>) = issues.groupBy { it.section }.map { Group(it.key, it.value.sortedWith(compareBy({ p -> severityRank(p.severity) }, { p -> p.line }, { p -> p.column }))) }
    fun sectionGroupKey(section: String?) = section.orEmpty()
    fun sectionGroupTitle(section: String?) = section?.takeIf { it.isNotBlank() }?.let { "[$it]" } ?: "文件"
    fun severityRank(severity: String) = when (severity) { "error" -> 0; "warn" -> 1; else -> 2 }
    fun groupIssuesInSection(issues: List<EditorDiagnostic>) = issues.groupBy { it.key }.map { KeyGroup(it.key, it.value) }
    fun shouldWrapKeyGroup(key: String?, count: Int) = !key.isNullOrBlank() && count >= 2
}
