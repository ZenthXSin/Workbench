package io.github.zenthxsin.rustwright.core.agent

import io.github.zenthxsin.rustwright.core.ai.AiChannel
import io.github.zenthxsin.rustwright.core.storage.KeyValueStore
import io.github.zenthxsin.rustwright.core.storage.SecretStore
import kotlinx.serialization.Serializable

/** 助手的非敏感设置（令牌单独放 [SecretStore]）。 */
@Serializable
data class AgentSettings(
    val baseUrl: String = io.github.zenthxsin.rustwright.core.ai.Relay.DEFAULT_BASE_URL,
    val chatModel: String = "",
    val imageModel: String = "",
    /** 向量模型；留空则相关功能关闭。 */
    val embeddingModel: String = "",
    val mode: ExecutionMode = ExecutionMode.ASK,
    val maxSteps: Int = 24,
)

/**
 * 助手的设置与线路：令牌、服务地址、模型，以及工具共用的审批策略和提问端口。
 * 对话本身由 Codex 内核执行（见 CodexWorkbench）。
 */
class AgentService(
    private val settingsStore: KeyValueStore,
    private val secrets: SecretStore,
    val tools: AgentToolRegistry,
    /** 审批弹窗端口；模块自己的审批流程（如锈匠写入工具）也复用它。 */
    val prompter: ApprovalPrompter,
    /** 向用户提问的端口。 */
    val interaction: UserInteraction?,
) {
    val approval = ApprovalPolicy(prompter)

    // 读出来就过一遍白名单：配置文件里的服务地址被改过也只会落回内置中转站
    var settings: AgentSettings = (settingsStore.get(SETTINGS_KEY, AgentSettings.serializer()) ?: AgentSettings()).sanitized()
        private set

    private fun AgentSettings.sanitized() = copy(baseUrl = io.github.zenthxsin.rustwright.core.runtime.net.RelayNodes.trusted(baseUrl))

    init {
        approval.mode = settings.mode
    }

    var token: String?
        get() = secrets.get(TOKEN_KEY)
        set(value) {
            if (value.isNullOrBlank()) secrets.remove(TOKEN_KEY) else secrets.put(TOKEN_KEY, value.trim())
        }

    val ready: Boolean get() = !token.isNullOrBlank() && settings.chatModel.isNotBlank()

    val channel: AiChannel?
        get() = token?.takeIf { it.isNotBlank() }?.let { AiChannel(settings.baseUrl, it, settings.chatModel, settings.imageModel.ifBlank { null }) }

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        val next = transform(settings).sanitized()
        settings = next
        settingsStore.put(SETTINGS_KEY, AgentSettings.serializer(), next)
        approval.mode = next.mode
    }

    companion object {
        const val DEFAULT_CONVERSATION = "main"
        private const val SETTINGS_KEY = "agent.settings"
        private const val TOKEN_KEY = "agent.token"
    }
}
