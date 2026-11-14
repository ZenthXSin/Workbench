package io.github.zenthxsin.rustwright.core.agent

/**
 * 执行模式（沿用助手）：
 * - [PLAN]：只读和规划，拒绝一切写入与外部副作用；
 * - [ASK]：写入、删除、生图等逐次询问用户（可「本会话内记住」）；
 * - [AUTO]：普通写入静默通过，危险操作（删除元数据目录、写 .git、启动游戏、花钱的调用）仍要确认。
 */
enum class ExecutionMode { PLAN, ASK, AUTO }

/** 工具声明自己会碰哪些工作区路径，供审批规则按路径记忆。 */
interface PathAware {
    fun touchedPaths(args: kotlinx.serialization.json.JsonObject): List<String>
}
