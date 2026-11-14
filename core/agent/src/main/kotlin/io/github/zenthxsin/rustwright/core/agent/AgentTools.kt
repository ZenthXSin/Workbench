package io.github.zenthxsin.rustwright.core.agent

import io.github.zenthxsin.rustwright.core.runtime.Contribution
import io.github.zenthxsin.rustwright.core.runtime.ExtensionPoint

/** 工具出现的场景。[ALWAYS] 的工具全局可用。 */
data class AgentScene(val id: String) {
    init { require(id.isNotBlank()) }
    companion object { val ALWAYS = AgentScene("always") }
}

/**
 * 一组工具：交给助手时是一个独立的 MCP 服务器（名字就是 [id]），在「连接器」里单独显示、单独开关。
 * 由提供工具的模块注册到 [AgentPoints.TOOLSETS]。
 */
class Toolset(
    override val id: String,
    val title: String,
    val description: String,
    override val order: Int = 100,
) : Contribution

/** Framework tool group; business groups are declared by plugins. */
object Toolsets { const val CORE = "rustwright-core" }

/** 事件：助手开始 / 结束干活（任一会话在跑）。 */
data class AgentBusy(val active: Boolean)

/** 助手相关的贡献点。 */
object AgentPoints {
    val TOOLSETS = ExtensionPoint<Toolset>("agent.toolsets")
    val SKILLS = ExtensionPoint<BundledSkill>("agent.skills")
    val INSTRUCTIONS = ExtensionPoint<AgentInstruction>("agent.instructions")
}

/** 模块在 init 时注册工具，每个工具属于一个 [Toolset]。 */
class AgentToolRegistry {
    private class Entry(val tool: Tool, val scenes: Set<AgentScene>, val toolset: String)

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun register(tool: Tool, vararg scenes: AgentScene, toolset: String = Toolsets.CORE) {
        check(tool.name !in entries) { "工具名重复：${tool.name}" }
        entries[tool.name] = Entry(tool, scenes.toSet().ifEmpty { setOf(AgentScene.ALWAYS) }, toolset)
    }

    /** 运行时注销。 */
    @Synchronized
    fun unregister(name: String) { entries.remove(name) }

    @Synchronized
    operator fun contains(name: String): Boolean = name in entries

    /** 某场景下可用的工具：该场景的 + 全局的。 */
    @Synchronized
    fun toolsFor(scene: AgentScene): List<Tool> =
        entries.values.filter { AgentScene.ALWAYS in it.scenes || scene in it.scenes }.map { it.tool }

    /** 工具所属的组。 */
    @Synchronized
    fun toolsetOf(name: String): String? = entries[name]?.toolset

    /** 按组分好的工具（组内保持注册顺序）。 */
    @Synchronized
    fun byToolset(): Map<String, List<Tool>> = entries.values.groupBy({ it.toolset }, { it.tool })

    val scenes: Set<AgentScene> @Synchronized get() =
        setOf(AgentScene.ALWAYS) + entries.values.flatMap { it.scenes }

    val all: List<Tool> @Synchronized get() = entries.values.map { it.tool }
}
