package io.github.zenthxsin.rustwright.ui.agent

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import io.github.zenthxsin.rustwright.core.agent.ApprovalAnswer
import io.github.zenthxsin.rustwright.core.agent.ApprovalPrompt
import io.github.zenthxsin.rustwright.core.agent.ApprovalPrompter
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import io.github.zenthxsin.rustwright.core.agent.ToolRisk
import io.github.zenthxsin.rustwright.core.agent.UserInteraction

/** 用 Arc 对话框实现助手的审批与提问端口。 */
object AgentPorts : ApprovalPrompter, UserInteraction {

    override suspend fun prompt(request: ApprovalPrompt): ApprovalAnswer {
        val what = when (request.risk) {
            ToolRisk.WRITE -> "修改文件"
            ToolRisk.DESTRUCTIVE -> "执行高影响操作"
            ToolRisk.EXTERNAL -> "调用外部服务（可能产生费用）"
            ToolRisk.READ -> "读取"
        }
        val body = buildString {
            append("${AppBrand.identity.assistant}想要").append(what).append("：").append(request.toolName)
            if (request.paths.isNotEmpty()) append("\n\n").append(request.paths.joinToString("\n") { "· $it" })
            request.preview?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it.take(1500)) }
        }
        val buttons = if (request.canRemember) listOf("拒绝", "本会话允许", "允许一次") else listOf("拒绝", "允许一次")
        return when (buttons.getOrNull(Dialogs.choose("需要你确认", body, buttons))) {
            "允许一次" -> ApprovalAnswer.ALLOW_ONCE
            "本会话允许" -> ApprovalAnswer.ALLOW_SESSION
            else -> ApprovalAnswer.DENY
        }
    }

    override suspend fun ask(conversationId: String, question: String, options: List<String>, allowFreeText: Boolean): String? {
        if (options.isNotEmpty()) {
            val buttons = if (allowFreeText) options + "其他…" else options
            val i = Dialogs.choose("${AppBrand.identity.assistant}想问你", question, buttons, highlight = -1)
            if (i < 0) return null
            if (allowFreeText && i == options.size) return Dialogs.input(question)
            return options[i]
        }
        return Dialogs.input(question)
    }
}
