package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.shell.AgentShell
import io.github.zenthxsin.rustwright.core.agent.Tool
import io.github.zenthxsin.rustwright.core.agent.ToolContext
import io.github.zenthxsin.rustwright.core.agent.ToolResult
import io.github.zenthxsin.rustwright.core.agent.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 助手工具：在内置 Linux（Alpine，工作区挂在 /workspace）里执行 shell 命令，过程显示在终端页的「助手」会话。
 * 风险为 EXTERNAL：每次都要用户确认，审批卡片显示完整命令。
 */
class RunCommandTool(private val shell: AgentShell) : Tool() {
    override val name = "run_command"
    override val description = "Run a bash command in the built-in Alpine Linux (workspace mounted at /workspace; git, Node 24 and python3 available; install more with `apk add`, e.g. `apk add openjdk21-jdk` for Java 21). " +
        "The command runs visibly in the user's terminal. Returns combined stdout/stderr and the exit code."
    override val risk = ToolRisk.EXTERNAL
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") { put("type", "string"); put("description", "Bash script to run") }
            putJsonObject("workdir") { put("type", "string"); put("description", "Working directory, default /workspace") }
            putJsonObject("timeout_seconds") { put("type", "integer"); put("description", "1-600, default 120") }
        }
        putJsonArray("required") { add(JsonPrimitive("command")) }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    override suspend fun preview(ctx: ToolContext, args: JsonObject) = "$ " + args.str("command").orEmpty()

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val command = args.str("command")?.takeIf { it.isNotBlank() } ?: return ToolResult.failure("invalid_args", "command is required")
        val timeout = ((args["timeout_seconds"] as? JsonPrimitive)?.intOrNull ?: 120).coerceIn(1, 600)
        return runCatching { shell.run(command, workDir = args.str("workdir") ?: "/workspace", timeoutMillis = timeout * 1000L) }.fold(
            { r ->
                val head = when {
                    r.timedOut -> "Timed out after ${timeout}s (process killed)."
                    else -> "Exit code ${r.exitCode}."
                }
                val text = head + (if (r.truncated) " Output truncated." else "") + "\n" + r.output
                if (r.timedOut || r.exitCode != 0) ToolResult(text, error = io.github.zenthxsin.rustwright.core.agent.ToolError("command_failed", head, retryable = r.timedOut)) else ToolResult(text)
            },
            { ToolResult.failure("linux_unavailable", it.message ?: "cannot run command", suggestions = listOf("Ask the user to install the built-in Linux on the 终端 page")) },
        )
    }
}
