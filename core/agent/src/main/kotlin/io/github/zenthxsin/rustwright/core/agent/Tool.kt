package io.github.zenthxsin.rustwright.core.agent

import kotlinx.serialization.json.JsonObject

/**
 * Rustwright 工具：由模块注册到 [AgentToolRegistry]，以 Codex 动态工具的形式交给助手，在 App 内执行。
 * [parameters] 是 JSON Schema（object）。
 */
abstract class Tool {
    abstract val name: String
    abstract val description: String
    abstract val parameters: JsonObject

    abstract suspend fun execute(userId: String, args: JsonObject): ToolResult

    /** 风险等级，决定是否需要用户确认；默认只读。 */
    open val risk: ToolRisk get() = ToolRisk.READ

    /** True only when execution enforces its own session-aware approval policy. */
    open val handlesApproval: Boolean get() = false

    /** Whether the assistant may discover this tool; manual tool interfaces remain available. */
    open val exposeToAssistant: Boolean get() = true

    /** 审批前的预览（diff、费用说明等）；默认无。 */
    open suspend fun preview(ctx: ToolContext, args: JsonObject): String? = null

    /** 带上下文的执行入口，默认委托给 [execute] (userId, args)。 */
    open suspend fun execute(ctx: ToolContext, args: JsonObject): ToolResult = execute(ctx.userId, args)
}

/**
 * 工具执行结果。[toAgent] 是给模型看的内容；[error] 非空表示调用失败。
 */
data class ToolResult(
    val toAgent: String,
    val error: ToolError? = null,
) {
    val isError: Boolean get() = error != null

    companion object {
        /** 失败结果：错误类型、是否值得重试、给模型的修正建议。 */
        fun failure(type: String, message: String, retryable: Boolean = false, suggestions: List<String> = emptyList()): ToolResult {
            val err = ToolError(type, message, retryable, suggestions)
            return ToolResult(toAgent = err.describe(), error = err)
        }
    }
}

/** 结构化工具错误。 */
data class ToolError(
    val type: String,
    val message: String,
    val retryable: Boolean = false,
    val suggestions: List<String> = emptyList(),
) {
    fun describe(): String = buildString {
        append("Error[").append(type).append("]: ").append(message)
        append(if (retryable) "\n(retryable)" else "\n(not retryable; change approach)")
        if (suggestions.isNotEmpty()) suggestions.joinTo(this, separator = "\n- ", prefix = "\nSuggestions:\n- ")
    }
}

/**
 * 风险等级：
 * - READ：只读（列目录、读文件、搜索）
 * - WRITE：修改工作区（写文件、改 INI）
 * - DESTRUCTIVE：不可逆或高影响（删除、批量覆盖、启动游戏）
 * - EXTERNAL：产生费用或外部副作用（生图、联网下载、执行命令）
 */
enum class ToolRisk { READ, WRITE, DESTRUCTIVE, EXTERNAL }

/** 一次调用的上下文：调用者、所属会话、向用户提问的端口（无 UI 时为 null）。 */
class ToolContext(
    val userId: String,
    val conversationId: String,
    val interaction: UserInteraction? = null,
    val projectDir: java.io.File? = null,
    val mode: ExecutionMode? = null,
    val stillCurrent: () -> Boolean = { true },
)

/** 工具向用户提问的端口（由 UI 实现，挂起直到用户回答；取消返回 null）。 */
interface UserInteraction {
    suspend fun ask(conversationId: String, question: String, options: List<String>, allowFreeText: Boolean): String?
}
