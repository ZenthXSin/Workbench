package io.github.zenthxsin.rustwright.core.agent

import kotlinx.serialization.json.JsonObject

/** 弹给用户的一次审批。 */
data class ApprovalPrompt(
    val conversationId: String,
    val toolName: String,
    val risk: ToolRisk,
    val paths: List<String>,
    /** 工具给出的预览（diff、费用说明等）。 */
    val preview: String?,
    /** 是否允许「本会话内记住」。 */
    val canRemember: Boolean,
)

enum class ApprovalAnswer { ALLOW_ONCE, ALLOW_SESSION, DENY }

/** 审批结果：放行，或拒绝并附给模型看的原因。 */
sealed interface ApprovalDecision {
    data object Allow : ApprovalDecision
    data class Deny(val reason: String) : ApprovalDecision
}

/** UI 实现：显示审批卡片并挂起到用户选择。 */
fun interface ApprovalPrompter {
    suspend fun prompt(request: ApprovalPrompt): ApprovalAnswer
}

/**
 * 助手审批策略（[ExecutionMode] + [SessionApprovalRules]）。
 * 只读工具永远放行；EXTERNAL（花钱 / 联网 / 执行命令）在任何模式下都不会静默通过，也不允许记住。
 */
class ApprovalPolicy(
    private val prompter: ApprovalPrompter,
    @Volatile var mode: ExecutionMode = ExecutionMode.ASK,
    val rules: SessionApprovalRules = SessionApprovalRules(),
) {

    suspend fun approve(tool: Tool, args: JsonObject, conversationId: String, preview: String? = null): ApprovalDecision {
        val risk = tool.risk
        val mode = this.mode
        val ruleKey = conversationId + "\u0000" + tool.name
        if (risk == ToolRisk.READ) return ApprovalDecision.Allow
        if (mode == ExecutionMode.PLAN) {
            return ApprovalDecision.Deny("当前为计划模式，只能读取和规划；请先给出方案，等用户切换到执行模式")
        }
        val paths = (tool as? PathAware)?.touchedPaths(args).orEmpty()
        val dangerous = risk == ToolRisk.EXTERNAL || risk == ToolRisk.DESTRUCTIVE || paths.any(::isProtectedPath)

        if (!dangerous) {
            if (mode == ExecutionMode.AUTO) {
                return ApprovalDecision.Allow
            }
            if (rules.allows(ruleKey, paths)) return ApprovalDecision.Allow
        }

        val canRemember = risk != ToolRisk.EXTERNAL && paths.isNotEmpty() && paths.none(::isProtectedPath)
        val answer = prompter.prompt(ApprovalPrompt(conversationId, tool.name, risk, paths, preview, canRemember))
        return when (answer) {
            ApprovalAnswer.ALLOW_ONCE -> ApprovalDecision.Allow
            ApprovalAnswer.ALLOW_SESSION -> {
                if (canRemember) rules.remember(ruleKey, paths)
                ApprovalDecision.Allow
            }
            ApprovalAnswer.DENY -> ApprovalDecision.Deny("用户拒绝了这次操作")
        }
    }

    companion object {
        /** 元数据目录：`.project`、`.workbench`、`.git`。 */
        fun isProtectedPath(path: String): Boolean {
            val n = SessionApprovalRules.normalize(path).lowercase()
            val protected = setOf(".project", ".workbench", ".git") +
                io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.configured("agent.protected_dirs", "")
                    .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            return n.isEmpty() || n.split('/').any { it in protected }
        }
    }
}
