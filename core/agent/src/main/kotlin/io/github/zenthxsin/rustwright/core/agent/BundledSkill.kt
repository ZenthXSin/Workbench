package io.github.zenthxsin.rustwright.core.agent

import io.github.zenthxsin.rustwright.core.runtime.Contribution

/** 模块随包技能；相对路径资源与 SKILL.md 一起安装，供 Codex 和工具模式共用。 */
class BundledSkill(
    override val id: String,
    val toolset: String,
    override val order: Int = 100,
    val files: () -> Map<String, ByteArray>,
) : Contribution {
    init { require(id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && id.length <= 48) { "Invalid bundled skill id: $id" } }
}

/** 模块声明领域规则，统一追加到当前会话；工具组停用时不注入。 */
class AgentInstruction(
    override val id: String,
    val toolset: String,
    val text: String,
    override val order: Int = 100,
) : Contribution
