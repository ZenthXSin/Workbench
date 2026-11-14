package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.codex.*
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.*
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import kotlinx.coroutines.CancellationException

/** 指令在本地分派；结构化操作走 app-server，不把 /xxx 当成聊天消息。 */
internal class SlashController(private val s: FusedShell) {
    private val wb get() = s.host.wb
    private var selectedSkill: CodexSkill? = null

    fun execute(text: String): Boolean {
        val invocation = SlashCommands.parse(text) ?: run { s.notify("没有这条指令，输入 / 查看列表"); return false }
        val arg = invocation.argument
        return try {
            when (invocation.command.english) {
                "permissions" -> { require(s.conversation()?.allowGoalContinuation != true) { "请先暂停目标再修改权限" }; Sheets.modeSheet(s) }
                "mcp" -> s.openTool("工具", ConnectorsPage(s))
                "skills" -> {
                    if (arg.isBlank()) skillCatalog()
                    else {
                        val name = arg.substringBefore(' ')
                        val task = arg.substringAfter(' ', "").trim()
                        if (task.isEmpty()) { s.fillCommandDraft("/技能 $name "); return false }
                        val c = conversation() ?: return false
                        action {
                            val matches = wb.listSkills(c.projectId).skills.filter { it.enabled && it.name == name }
                            val skill = selectedSkill?.let { chosen -> matches.firstOrNull { it.path == chosen.path } }
                                ?: matches.singleOrNull() ?: error("技能不存在、已停用或名称重复，请从 /技能 列表选择")
                            wb.send(c.id, task, skills = listOf(CodexSkillRef(skill.name, skill.path)))
                        }
                    }
                }
                "plugins" -> pluginCatalog()
                "goal" -> { val c = conversation() ?: return false; action { goal(c, arg) } }
                "plan" -> {
                    val c = conversation() ?: return false
                    val enabled = when (arg.lowercase()) { "关闭", "off" -> false; "开启", "on" -> true; "" -> !c.planMode; else -> true }
                    wb.setPlanMode(c.id, enabled)
                    s.notify(if (enabled) "已开启计划模式" else "已关闭计划模式")
                    if (arg.isNotBlank() && arg.lowercase() !in setOf("开启", "关闭", "on", "off")) wb.send(c.id, arg)
                }
                "review" -> {
                    val c = conversation() ?: return false
                    wb.send(c.id, "审查当前项目改动，重点检查错误、回归和项目兼容性。仅审查，不修改文件。" +
                        arg.takeIf { it.isNotBlank() }?.let { "\n审查重点：$it" }.orEmpty(), operation = CodexOperation.REVIEW)
                }
                "compact" -> {
                    val c = existing() ?: return false
                    require(c.turns.isNotEmpty()) { "当前对话还没有需要压缩的内容" }
                    wb.send(c.id, "压缩当前上下文", operation = CodexOperation.COMPACT)
                }
                "recap" -> {
                    val c = existing() ?: return false
                    wb.send(c.id, "请根据本会话总结：已完成的工作、当前进度、待办事项，以及实际执行的验证和结果。不要调用工具或修改文件。" +
                        arg.takeIf { it.isNotBlank() }?.let { "\n补充要求：$it" }.orEmpty())
                }
                "init" -> {
                    val c = conversation() ?: return false
                    require(c.mode != CodexMode.ASK) { "初始化需要写入权限，请先调整 /权限" }
                    wb.send(c.id, "检查当前项目并创建或完善根目录 AGENTS.md，记录项目结构、开发约定和可用验证方法；保留已有有效说明，不修改其他文件。" +
                        "遵守当前项目的开发说明，不要把初始化当成任务完成。" +
                        arg.takeIf { it.isNotBlank() }?.let { "\n补充要求：$it" }.orEmpty())
                }
                "diff" -> {
                    val c = s.conversation()
                    val p = c?.let { wb.project(it.projectId) } ?: s.currentProject() ?: error("请先选择项目")
                    action {
                        val actual = s.host.projectDiff(p)
                        val recorded = c?.turns.orEmpty().mapNotNull { it.diff?.takeIf(String::isNotBlank) }.joinToString("\n\n")
                        val content = actual ?: if (recorded.isNotBlank()) "未启用 Git，下面仅为本会话的改动记录：\n\n$recorded" else "项目未启用 Git，当前会话也没有改动记录。"
                        s.openTool("改动", CommandPage("改动").apply {
                            body.add(K.mono(content.take(300_000), 13f, K.text).apply { setWrap(true) }).growX().left()
                        })
                    }
                }
            }
            true
        } catch (e: Exception) { s.notify(e.message ?: "指令执行失败"); false }
    }

    private fun conversation(): CodexConversation? = s.ensureCommandConversation()
    private fun existing(): CodexConversation? = s.conversation() ?: run { s.notify("请先打开一个对话"); null }
    private fun action(block: suspend () -> Unit) { s.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { s.notify("指令失败：${e.message}") }
    } }

    private suspend fun goal(c: CodexConversation, arg: String) {
        if (arg in setOf("暂停", "清除", "完成")) {
            require(!c.status.active) { "请先停止当前任务再修改目标" }
            if (arg == "清除") wb.clearGoal(c.id) else wb.setGoal(c.id, status = if (arg == "暂停") "paused" else "complete")
            s.notify("目标已$arg"); return
        }
        if (arg == "继续") { wb.setGoal(c.id); s.notify("目标已继续"); return }
        if (arg.isNotBlank()) { editGoal(c, arg); return }
        val value = if (c.status.active) c.goal else wb.getGoal(c.id)
        val g = ListGroup()
        g.row(details(value?.objective ?: "还没有设置目标", value?.let { "${goalStatus(it.status)} · 已使用 ${it.tokensUsed} / ${it.tokenBudget?.toString() ?: "不限"} tokens" }))
        g.row(listRow(if (value == null) "设置目标" else "编辑目标", "会自动继续任务，并消耗当前模型额度", PI.edit) {
            s.sheets.close(); action { editGoal(c, value?.objective.orEmpty()) }
        })
        if (value != null) {
            g.row(listRow(if (value.status == "active") "暂停目标" else "继续目标", null, PI.play) {
                s.sheets.close(); action {
                    if (value.status == "active") wb.interrupt(c.id)
                    wb.setGoal(c.id, status = if (value.status == "active") "paused" else "active")
                    s.notify("目标已更新")
                }
            })
            g.row(listRow("清除目标", "正在执行时会先停止任务", PI.close) {
                s.sheets.close(); action { wb.interrupt(c.id); wb.clearGoal(c.id); s.notify("目标已清除") }
            })
        }
        s.sheets.open(SheetPage("目标", Table().top().apply { add(g).growX().pad(dp(12f)) }, height = 400f))
    }
    private suspend fun editGoal(c: CodexConversation, initial: String) {
        require(!c.status.active) { "请先停止当前任务再编辑目标" }
        val objective = Dialogs.input("任务目标", hint = "描述可验证的完成条件", initial = initial)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val raw = Dialogs.input("目标 Token 预算", hint = "自动续行会消耗模型额度", initial = (c.goal?.tokenBudget ?: 100_000).toString()) ?: return
        val budget = raw.trim().toLongOrNull()?.takeIf { it > 0 } ?: error("请输入大于零的 Token 预算")
        wb.setGoal(c.id, objective, budget = budget)
        s.notify("目标已设置，${AppBrand.identity.assistant}会在预算内继续执行")
    }
    private fun goalStatus(status: String) = when (status) {
        "active" -> "进行中"; "paused" -> "已暂停"; "complete" -> "已完成"
        "blocked" -> "遇到阻碍"; "budgetLimited" -> "已达预算"; "usageLimited" -> "额度受限"; else -> status
    }

    private fun details(text: String, sub: String? = null) = Table(K.fill(K.surface, 0f)).apply {
        margin(dp(14f), dp(18f), dp(14f), dp(18f))
        add(K.para(text, 16f, K.text, lh = 23f)).growX().left().row()
        sub?.let { add(K.para(it, 13f, K.subtle, lh = 19f)).growX().left().padTop(dp(8f)) }
    }
    private fun skillScope(scope: String) = when (scope) { "user" -> "全局"; "repo" -> "项目"; "system" -> "系统"; "admin" -> "管理员"; else -> scope }

    private fun skillCatalog() {
        val project = s.conversation()?.let { wb.project(it.projectId) } ?: s.currentProject() ?: error("请先选择项目")
        val page = CommandPage("技能")
        fun refresh() { action {
            page.message("正在读取技能…")
            try {
                val result = wb.listSkills(project.id)
                page.body.clearChildren()
                if (result.skills.isEmpty()) page.body.add(K.para("没有发现技能。模组制作完成后自动生成的技能也会出现在这里。", 15f, K.subtle)).growX().row()
                val group = ListGroup()
                result.skills.forEach { skill -> group.row(listRow(skill.name, "${if (skill.enabled) "已启用" else "已停用"} · ${skillScope(skill.scope)} · ${skill.description}", PI.bolt) {
                    val actions = ListGroup()
                    actions.row(details(skill.description.ifBlank { skill.name }, skill.path))
                    if (skill.enabled) actions.row(listRow("使用技能", "选择后在输入框补充任务", PI.play) {
                        selectedSkill = skill; s.sheets.close(); s.fillCommandDraft("/技能 ${skill.name} ")
                    })
                    actions.row(listRow(if (skill.enabled) "停用技能" else "启用技能", null, PI.sliders) {
                        s.sheets.close(); action { wb.setSkillEnabled(skill, !skill.enabled); refresh() }
                    })
                    s.sheets.open(SheetPage(skill.name, Table().top().apply { add(actions).growX().pad(dp(12f)) }, height = 400f))
                }) }
                page.body.add(group).growX().row()
                result.errors.forEach { page.body.add(K.para(it, 13f, K.danger)).growX().padTop(dp(12f)).row() }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { page.message("读取技能失败：${e.message}\n可点右上角重试。") }
        } }
        page.bar.action(PI.refresh, "刷新技能") { refresh() }
        s.openTool("技能", page); refresh()
    }

    private fun pluginCatalog() {
        val project = s.conversation()?.let { wb.project(it.projectId) } ?: s.currentProject() ?: error("请先选择项目")
        val page = CommandPage("插件")
        fun refresh() { action {
            page.message("正在读取插件…")
            try {
                val result = wb.listPlugins(project.id)
                page.body.clearChildren()
                if (result.plugins.isEmpty()) page.body.add(K.para("当前没有可用插件。", 15f, K.subtle)).growX().row()
                val group = ListGroup()
                result.plugins.forEach { plugin -> group.row(listRow(plugin.name, "${if (plugin.installed) if (plugin.enabled) "已安装" else "已停用" else "未安装"} · ${plugin.marketplace}", PI.box) {
                    val actions = ListGroup()
                    actions.row(details(plugin.description.ifBlank { plugin.name }, "来源：${plugin.marketplace}" + plugin.capabilities.takeIf { it.isNotEmpty() }?.joinToString("、", "\n能力：").orEmpty()))
                    val label = if (plugin.installed) "卸载插件" else "安装插件"
                    if (plugin.installed || plugin.availability == "AVAILABLE" && plugin.installPolicy != "NOT_AVAILABLE") actions.row(listRow(label, null, if (plugin.installed) PI.close else PI.down) {
                        s.sheets.close(); action {
                            if (!Dialogs.confirm(label, "${plugin.name}\n来源：${plugin.marketplace}\n${plugin.description}", ok = label)) return@action
                            if (plugin.installed) { wb.uninstallPlugin(plugin); s.notify("插件已卸载") }
                            else {
                                val result = wb.installPlugin(plugin)
                                s.notify(if (result.appsNeedingAuth.isEmpty()) "插件已安装" else "已安装，还需为这些应用登录授权：${result.appsNeedingAuth.joinToString("、")}")
                            }
                            refresh()
                        }
                    }) else actions.row(listRow("暂不可安装", if (plugin.installPolicy == "NOT_AVAILABLE") "当前工作区不允许安装" else "管理员已停用", PI.info))
                    s.sheets.open(SheetPage(plugin.name, Table().top().apply { add(actions).growX().pad(dp(12f)) }, height = 400f))
                }) }
                page.body.add(group).growX().row()
                result.errors.forEach { page.body.add(K.para(it, 13f, K.danger)).growX().padTop(dp(12f)).row() }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { page.message("读取插件失败：${e.message}\n可点右上角重试。") }
        } }
        page.bar.action(PI.refresh, "刷新插件") { refresh() }
        s.openTool("插件", page); refresh()
    }
}

private class CommandPage(title: String) : Table(), OwnHeader {
    val bar = PageBar(title)
    val body = Table().top().left().apply { margin(dp(12f), dp(16f), dp(24f), dp(16f)) }
    init { top(); pageBar(bar); add(ScrollPane(body).apply { setScrollingDisabledX(true) }).grow() }
    override fun bindBack(back: () -> Unit) = bar.bindBack(back)
    fun message(text: String) { body.clearChildren(); body.add(K.para(text, 15f, K.subtle)).growX().left() }
}
