package io.github.zenthxsin.rustwright.core.codex

import kotlinx.serialization.json.*

internal fun parseGoal(value: JsonObject) = CodexGoal(value.str("objective").orEmpty(), value.str("status").orEmpty(),
    value.long("tokenBudget"), value.long("tokensUsed") ?: 0)

suspend fun CodexWorkbench.getGoal(id: String): CodexGoal? = threadCommand(id) { c, rpc, thread, validate ->
    rpc.request("thread/goal/get", buildJsonObject { put("threadId", thread) }).objOrNull()?.obj("goal")?.let(::parseGoal).also {
        validate(); c.goal = it
        if (it?.status != "active") c.allowGoalContinuation = false
    }
}

suspend fun CodexWorkbench.setGoal(id: String, objective: String? = null, status: String = "active", budget: Long? = null): CodexGoal =
    threadCommand(id) { c, rpc, thread, validate ->
        require(status in setOf("active", "paused", "complete")) { "目标状态无效" }
        objective?.let { require(it.isNotBlank() && it.length <= 8000) { "目标须为 1–8000 字符" } }
        budget?.let { require(it > 0) { "Token 预算必须大于零" } }
        val previous = c.allowGoalContinuation
        c.allowGoalContinuation = status == "active"
        try {
            val result = rpc.request("thread/goal/set", buildJsonObject {
                put("threadId", thread); put("origin", "user"); put("status", status)
                objective?.let { put("objective", it) }; budget?.let { put("tokenBudget", it) }
            }).objOrNull()?.obj("goal") ?: throw CodexException("Codex 没有返回目标")
            validate()
            parseGoal(result).also { c.goal = it; c.allowGoalContinuation = it.status == "active" }
        } catch (e: Exception) {
            if (runCatching { validate() }.isSuccess) c.allowGoalContinuation = previous
            throw e
        }
    }

suspend fun CodexWorkbench.clearGoal(id: String) = threadCommand(id) { c, rpc, thread, validate ->
    rpc.request("thread/goal/clear", buildJsonObject { put("threadId", thread); put("origin", "user") })
    validate()
    c.goal = null; c.allowGoalContinuation = false
}

data class CodexSkill(val name: String, val description: String, val path: String, val scope: String, val enabled: Boolean)
data class CodexSkillList(val skills: List<CodexSkill>, val errors: List<String>)
suspend fun CodexWorkbench.listSkills(projectId: String): CodexSkillList {
    val p = project(projectId) ?: throw CodexException("项目不存在")
    val data = commandRequest("skills/list", buildJsonObject { putJsonArray("cwds") { add(p.path) }; put("forceReload", true) })
    val entries = (data["data"] as? JsonArray).orEmpty().mapNotNull { it.objOrNull() }
    return CodexSkillList(entries.flatMap { (it["skills"] as? JsonArray).orEmpty() }.mapNotNull {
        val skill = it.objOrNull() ?: return@mapNotNull null
        val name = skill.str("name") ?: return@mapNotNull null
        val path = skill.str("path") ?: return@mapNotNull null
        CodexSkill(name, skill.str("description").orEmpty(), path, skill.str("scope").orEmpty(), skill["enabled"] != JsonPrimitive(false))
    }.distinctBy { it.path }, entries.flatMap { (it["errors"] as? JsonArray).orEmpty() }.mapNotNull {
        it.objOrNull()?.let { error -> listOfNotNull(error.str("path"), error.str("message")).joinToString("：") }
    })
}
suspend fun CodexWorkbench.setSkillEnabled(skill: CodexSkill, enabled: Boolean) {
    commandRequest("skills/config/write", buildJsonObject { put("path", skill.path); put("enabled", enabled) })
}

data class CodexPlugin(val id: String, val name: String, val description: String, val installed: Boolean, val enabled: Boolean,
    val marketplace: String, val marketplacePath: String?, val availability: String, val installPolicy: String = "AVAILABLE",
    val capabilities: List<String> = emptyList())
data class CodexPluginList(val plugins: List<CodexPlugin>, val errors: List<String>)
suspend fun CodexWorkbench.listPlugins(projectId: String): CodexPluginList {
    val p = project(projectId) ?: throw CodexException("项目不存在")
    val data = commandRequest("plugin/list", buildJsonObject { putJsonArray("cwds") { add(p.path) } })
    val markets = (data["marketplaces"] as? JsonArray).orEmpty().mapNotNull { it.objOrNull() }
    return CodexPluginList(markets.flatMap { market ->
        (market["plugins"] as? JsonArray).orEmpty().mapNotNull { element ->
            val plugin = element.objOrNull() ?: return@mapNotNull null
            val id = plugin.str("id") ?: return@mapNotNull null
            CodexPlugin(id, plugin.str("name") ?: id,
                plugin.obj("interface")?.str("shortDescription") ?: plugin.obj("interface")?.str("longDescription").orEmpty(),
                plugin["installed"] == JsonPrimitive(true), plugin["enabled"] == JsonPrimitive(true), market.str("name").orEmpty(),
                market.str("path"), plugin.str("availability") ?: "AVAILABLE", plugin.str("installPolicy") ?: "AVAILABLE",
                (plugin.obj("interface")?.get("capabilities") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
        }
    }.distinctBy { it.id }, (data["marketplaceLoadErrors"] as? JsonArray).orEmpty().map { it.objOrNull()?.str("message") ?: it.toString() })
}
data class CodexPluginInstallResult(val appsNeedingAuth: List<String>)
suspend fun CodexWorkbench.installPlugin(plugin: CodexPlugin): CodexPluginInstallResult {
    val result = commandRequest("plugin/install", buildJsonObject {
        put("pluginName", plugin.name)
        plugin.marketplacePath?.let { put("marketplacePath", it) } ?: put("remoteMarketplaceName", plugin.marketplace)
    })
    return CodexPluginInstallResult((result["appsNeedingAuth"] as? JsonArray).orEmpty().mapNotNull { it.objOrNull()?.str("name") })
}
suspend fun CodexWorkbench.uninstallPlugin(plugin: CodexPlugin) {
    commandRequest("plugin/uninstall", buildJsonObject { put("pluginId", plugin.id) })
}
