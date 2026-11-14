package io.github.zenthxsin.rustwright.core.agent

import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 调用所属的项目快照；[directory] 是平台转换后的本机绝对目录，不随 UI 切换。
 * [conversationId] 区分同一项目里的会话，供地图等带编辑状态的工具隔离数据。
 */
data class ToolProject(val id: String, val directory: File, val conversationId: String? = null, val freeBenefits: Boolean = false)

/**
 * 接收显式项目的工具入口，与审批用的 [PathAware] 相互独立。
 * 后台会话必须使用这里传入的目录，不能回退到 UI 的当前项目。
 */
interface ProjectAware {
    suspend fun executeInProject(userId: String, args: JsonObject, project: ToolProject): ToolResult
}
