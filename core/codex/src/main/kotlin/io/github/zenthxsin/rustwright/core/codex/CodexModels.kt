package io.github.zenthxsin.rustwright.core.codex

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 执行权限模式，与在线 Codex 工作台一致。 */
@Serializable
enum class CodexMode(val id: String, val label: String, val hint: String) {
    CODE("code", "逐次审批", "命令和文件修改需要你确认"),
    AUTO("auto", "自动批准", "自动批准命令、文件和权限请求"),
    FULL("full-access", "完全访问", "可操作 Linux 里的文件、命令与网络，不再请求审批"),
    ASK("ask", "只读", "只查看和回答，不修改文件");

    // 只读必须由执行沙盒限制；环境不支持沙盒时命令失败，不能降级为完全访问。
    val approvalPolicy: String get() = when (this) { AUTO, FULL, ASK -> "never"; CODE -> "on-request" }
    val sandbox: String get() = if (this == ASK) "read-only" else "danger-full-access"
    val sandboxPolicy: JsonObject get() = buildJsonObject {
        put("type", if (this@CodexMode == ASK) "readOnly" else "dangerFullAccess")
        if (this@CodexMode == ASK) put("networkAccess", true)
    }
    val autoApproves: Boolean get() = this == AUTO || this == FULL
}

@Serializable
enum class CodexStatus { IDLE, STARTING, RUNNING, WAITING, INTERRUPTED, ERROR;
    val active: Boolean get() = this == STARTING || this == RUNNING || this == WAITING
}

/** 项目：Linux 里的一个目录，下面多条会话共享文件。 */
@Serializable
class CodexProject(
    val id: String,
    var name: String,
    /** Linux 内的绝对路径（如 /workspace/demo_tank）。 */
    val path: String,
    var archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt,
)

/** Codex tokenUsage 的一组计数；cached 是 input 里命中缓存的部分，reasoning 是 output 里的思考部分。 */
@Serializable
data class CodexTokenCounts(
    val input: Long = 0,
    val cached: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val total: Long = 0,
)

@Serializable
class CodexConversation(
    val id: String,
    var projectId: String,
    var title: String = "新会话",
    var threadId: String? = null,
    /** 线程是在哪条线路（modelProvider）上开的；Codex 不允许已有线程换线路，换了就开新线程接着聊。 */
    var threadProvider: String? = null,
    var model: String,
    var effort: String? = null,
    var mode: CodexMode = CodexMode.CODE,
    val turns: MutableList<CodexTurn> = mutableListOf(),
    var status: CodexStatus = CodexStatus.IDLE,
    var activeTurnId: String? = null,
    var lastError: String? = null,
    var archived: Boolean = false,
    val queue: MutableList<CodexQueued> = mutableListOf(),
    var totalTokens: Long = 0,
    /** 当前上下文占用（最近一轮请求的 token 数，tokenUsage.last）。 */
    var contextTokens: Long = 0,
    /** 模型上下文窗口（tokenUsage.modelContextWindow）；0 = 未知。 */
    var contextWindow: Long = 0,
    /** 最近一次请求的 token 明细（tokenUsage.last）。 */
    var lastUsage: CodexTokenCounts = CodexTokenCounts(),
    /** 本对话累计 token 明细（tokenUsage.total）。 */
    var totalUsage: CodexTokenCounts = CodexTokenCounts(),
    /** 第一次请求的输入 token：系统提示、工具定义、项目说明加第一条消息，用来估算上下文里的固定开销。 */
    var baseTokens: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt,
) {
    /** 待处理的审批 / 提问（进程重启后失效，不持久化）。 */
    @Transient val approvals: MutableList<CodexApproval> = mutableListOf()
    /** 下次开线程时把对话文字带过去（换线路 / Codex 记录丢失）。 */
    @Transient var restoreNext: Boolean = false
    /** 已发出、Codex 还没回显成 userMessage 的消息（界面先显示出来）。 */
    @Transient var pendingPrompt: String? = null
    var planMode: Boolean = false
    var goal: CodexGoal? = null
    /** 只有用户明确启用目标，才允许接收服务端自动续行。 */
    var allowGoalContinuation: Boolean = false
}

@Serializable
class CodexTurn(
    val id: String,
    var status: String = "inProgress",
    val items: MutableList<CodexItem> = mutableListOf(),
    var error: String? = null,
    var diff: String? = null,
    var plan: List<CodexPlanStep> = emptyList(),
    /** 被用户删除显示的一方（"user" / "assistant"），与网页 deletedRoles 一致。 */
    val deletedRoles: MutableList<String> = mutableListOf(),
    val startedAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null,
)

/**
 * 一条过程记录（用户消息、回复、推理、命令、文件修改、工具调用……）。
 * 字段按 [type] 取用，未知类型把原始 JSON 放进 [text]。
 */
@Serializable
class CodexItem(
    val id: String,
    val type: String,
    var text: String = "",
    var status: String? = null,
    var command: String? = null,
    var cwd: String? = null,
    var output: String = "",
    var exitCode: Int? = null,
    var durationMs: Long? = null,
    var changes: List<CodexFileChange> = emptyList(),
    var server: String? = null,
    var tool: String? = null,
    var arguments: String? = null,
    var result: String? = null,
    var error: String? = null,
    var summaryIndex: Int = 0,
) {
    val running: Boolean get() = status == "inProgress"
    val failed: Boolean get() = status == "failed" || status == "declined" || error != null || (exitCode ?: 0) != 0
}

@Serializable
data class CodexFileChange(val path: String, val kind: String, val diff: String) {
    val added: Int get() = diff.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") }
    val removed: Int get() = diff.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
}

@Serializable
data class CodexPlanStep(val step: String, val status: String)

@Serializable
data class CodexQueued(val id: String, val text: String, val images: List<String> = emptyList(), val skills: List<CodexSkillRef> = emptyList())

@Serializable data class CodexSkillRef(val name: String, val path: String)
@Serializable data class CodexGoal(val objective: String, val status: String, val tokenBudget: Long? = null, val tokensUsed: Long = 0)
enum class CodexOperation { MESSAGE, REVIEW, COMPACT }

/** 一个问题（item/tool/requestUserInput）。 */
data class CodexQuestion(val id: String, val header: String?, val question: String, val options: List<String>, val multiSelect: Boolean, val allowOther: Boolean, val secret: Boolean)

/** Codex 发来的需要用户处理的请求。 */
class CodexApproval(
    val id: String,
    val conversationId: String,
    internal val requestId: JsonElement,
    val method: String,
    val params: JsonObject,
) {
    val kind: Kind = when (method) {
        "item/commandExecution/requestApproval" -> Kind.COMMAND
        "item/fileChange/requestApproval" -> Kind.FILE_CHANGE
        "item/permissions/requestApproval" -> Kind.PERMISSIONS
        "item/tool/requestUserInput" -> Kind.QUESTION
        TOOL_APPROVAL, MCP_TOOL_APPROVAL -> Kind.TOOL
        else -> Kind.OTHER
    }

    enum class Kind { COMMAND, FILE_CHANGE, PERMISSIONS, QUESTION, TOOL, OTHER }

    val command: String? get() = params.str("command") ?: (params["command"] as? JsonArray)?.joinToString(" ") { it.text.orEmpty() }
    val cwd: String? get() = params.str("cwd")
    val reason: String? get() = params.str("reason")

    val questions: List<CodexQuestion> get() = (params["questions"] as? JsonArray).orEmpty().mapNotNull { e ->
        val q = e.objOrNull() ?: return@mapNotNull null
        val id = q.str("id") ?: return@mapNotNull null
        val text = q.str("question") ?: return@mapNotNull null
        CodexQuestion(
            id, q.str("header"), text,
            (q["options"] as? JsonArray).orEmpty().mapNotNull { it.objOrNull()?.str("label") }.distinct(),
            (q["multiSelect"] as? JsonPrimitive)?.content == "true",
            (q["isOther"] as? JsonPrimitive)?.content != "false",
            (q["isSecret"] as? JsonPrimitive)?.content == "true",
        )
    }

    companion object {
        /** Rustwright 动态工具在非自动模式下的审批（不是 Codex 原生请求）。 */
        const val TOOL_APPROVAL = "rustwright/tool/approval"
        /** Codex 调用会改东西的 MCP 工具前的确认（mcpServer/elicitation/request），按工具审批显示。 */
        const val MCP_TOOL_APPROVAL = "rustwright/mcp/approval"
    }
}

/** 用户对审批的回答。 */
sealed interface CodexAnswer {
    data object Accept : CodexAnswer
    data object AcceptForSession : CodexAnswer
    data object Decline : CodexAnswer
    data class Answers(val answers: Map<String, List<String>>) : CodexAnswer
}

/** 交给 Codex 的 Rustwright 工具（以 dynamicTools 注册，调用在 App 内执行）。 */
class CodexTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    /** 会修改文件或产生费用：非自动模式下先请用户确认。 */
    val needsApproval: Boolean,
    val execute: suspend (call: CodexToolCall) -> CodexToolResult,
)

/** 一次工具调用：参数和发起它的项目 / 会话（用于切换当前模组、同步审批模式）。 */
class CodexToolCall(
    val arguments: JsonObject,
    val project: CodexProject?,
    val conversation: CodexConversation,
    /** 发起调用时在状态线程捕获；后台工具不再读取可被 UI 改变的会话权限。 */
    val mode: CodexMode = conversation.mode,
    /** 模型与费用线路和权限一样，在工具派发时捕获。 */
    val model: String = conversation.model,
    /** 派发时的轮次快照，后台工具不能读取之后切换的 activeTurnId。 */
    val turnId: String? = conversation.activeTurnId,
)

data class CodexToolResult(val success: Boolean, val text: String)

/** 一个 MCP 服务器的状态：[status] 为 connected / starting / failed / disabled…（null = 还没启动）。 */
data class CodexMcpServer(val name: String, val status: String?, val tools: Int, val error: String?, val target: String)
