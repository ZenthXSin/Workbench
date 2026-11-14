package io.github.zenthxsin.rustwright.fused

internal data class SlashCommand(val name: String, val english: String, val description: String, val icon: String)
internal data class SlashInvocation(val command: SlashCommand, val argument: String)

internal object SlashCommands {
    private val p get() = io.github.zenthxsin.rustwright.ui.kit.PI
    val all = listOf(
        SlashCommand("目标", "goal", "设置或查看长期任务目标", p.clock),
        SlashCommand("计划", "plan", "先制定计划，再开始实施", p.plan),
        SlashCommand("审查", "review", "审查修改与客户端兼容性", p.check),
        SlashCommand("改动", "diff", "查看项目文件的修改", p.file),
        SlashCommand("压缩", "compact", "压缩上下文，继续当前任务", p.layers),
        SlashCommand("总结", "recap", "总结进度、待办和验证结果", p.plan),
        SlashCommand("权限", "permissions", "设置操作权限与审批方式", p.sliders),
        SlashCommand("技能", "skills", "查看、选择和启停技能", p.bolt),
        SlashCommand("工具", "mcp", "查看 MCP 工具与连接", p.plug),
        SlashCommand("插件", "plugins", "浏览和管理 Codex 插件", p.box),
        SlashCommand("初始化", "init", "创建项目的 AGENTS.md 开发说明", p.file),
    )
    fun parse(text: String): SlashInvocation? {
        val raw = text.trim()
        if (!raw.startsWith('/')) return null
        val parts = raw.drop(1).split(Regex("\\s+"), limit = 2)
        val command = all.firstOrNull { it.name == parts[0] || it.english.equals(parts[0], true) } ?: return null
        return SlashInvocation(command, parts.getOrElse(1) { "" }.trim())
    }
    fun suggestions(text: String): List<SlashCommand> {
        val raw = text.trimStart()
        if (!raw.startsWith('/') || raw.drop(1).any { it.isWhitespace() || it == '/' }) return emptyList()
        val query = raw.drop(1)
        return all.filter { it.name.contains(query) || it.english.startsWith(query, true) }
    }
    /** 只有开头是已知指令（中文名或英文别名）才算指令；「/sdcard 打不开」「/tmp 满了」这类普通消息照常发给助手。 */
    fun isCommand(text: String): Boolean = parse(text) != null
}
