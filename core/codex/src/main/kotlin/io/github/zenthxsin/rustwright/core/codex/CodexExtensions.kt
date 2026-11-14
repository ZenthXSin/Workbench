package io.github.zenthxsin.rustwright.core.codex

import io.github.zenthxsin.rustwright.core.runtime.Contribution
import io.github.zenthxsin.rustwright.core.runtime.ExtensionPoint

/** A tool requiring the immutable conversation/turn snapshot may implement this capability. */
interface CodexCallTool {
    suspend fun executeCall(call: CodexToolCall): CodexToolResult
}

class CodexLifecycle(
    override val id: String,
    override val order: Int = 100,
    val deleted: (String) -> Unit = {},
    val finished: (CodexConversation, CodexTurn) -> Unit = { _, _ -> },
) : Contribution

class ProjectInstruction(
    override val id: String,
    val text: (CodexProject) -> String?,
    override val order: Int = 100,
    val toolset: String? = null,
) : Contribution

object CodexPoints {
    val LIFECYCLE = ExtensionPoint<CodexLifecycle>("codex.lifecycle")
    val INSTRUCTIONS = ExtensionPoint<ProjectInstruction>("codex.project.instructions")
}
