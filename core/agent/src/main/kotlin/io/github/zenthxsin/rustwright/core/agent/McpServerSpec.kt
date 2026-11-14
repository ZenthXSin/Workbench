package io.github.zenthxsin.rustwright.core.agent

import kotlinx.serialization.Serializable

/**
 * 一个 MCP 服务器：[target] 以 http(s):// 开头走 HTTP，否则视为在内置 Linux 里执行的 stdio 命令，
 * 例如 `npx -y @modelcontextprotocol/server-filesystem /workspace`。连接由 Codex 负责。
 */
@Serializable
data class McpServerSpec(val name: String, val target: String, val enabled: Boolean = true) {
    val isHttp: Boolean get() = target.startsWith("http://") || target.startsWith("https://")
}
