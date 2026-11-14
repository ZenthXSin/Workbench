package io.github.zenthxsin.rustwright.core.agent

import io.github.zenthxsin.rustwright.core.runtime.Extensions
import kotlinx.serialization.json.JsonObject

/** 无 UI 的能力目录。工具模式和助手读取同一份注册信息，避免维护第二套工具清单。 */
data class ToolCapability(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    /** 外层工具审批等级；内部逐文件审批的工具仍可能写入，不能据此绕过执行入口。 */
    val risk: ToolRisk,
    val acceptsProjectSnapshot: Boolean,
    val scenes: Set<AgentScene>,
)

data class ToolCapabilityGroup(
    val id: String,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val tools: List<ToolCapability>,
    val skillIds: List<String> = emptyList(),
)

/**
 * 每次读取均反映模块注册/注销与启停状态；不缓存 Tool 或当前界面的项目。
 * 目录只用于发现、参数表单和风险展示。调用仍须经现有审批入口，或在用户操作下调用模块 api 的领域服务。
 */
class ToolCapabilityCatalog(private val registry: AgentToolRegistry, private val extensions: Extensions) {
    fun groups(disabled: Set<String> = emptySet(), scene: AgentScene? = null): List<ToolCapabilityGroup> {
        val byGroup = registry.byToolset()
        val scenes = registry.scenes.associateWith { s -> registry.toolsFor(s).mapTo(HashSet()) { it.name } }
        val declared = extensions.all(AgentPoints.TOOLSETS).associateBy { it.id }
        return byGroup.mapNotNull { (id, tools) ->
            val available = tools.filter { scene == null || it.name in scenes[scene].orEmpty() }
            if (available.isEmpty()) return@mapNotNull null
            val group = declared[id]
            ToolCapabilityGroup(id, group?.title ?: id, group?.description.orEmpty(), id !in disabled,
                available.map { tool -> ToolCapability(tool.name, tool.description, tool.parameters, tool.risk,
                    tool is ProjectAware, scenes.filterValues { tool.name in it }.keys.toSet()) },
                extensions.all(AgentPoints.SKILLS).filter { it.toolset == id }.map { it.id })
        }.sortedWith(compareBy({ declared[it.id]?.order ?: Int.MAX_VALUE }, { it.id }))
    }
}
