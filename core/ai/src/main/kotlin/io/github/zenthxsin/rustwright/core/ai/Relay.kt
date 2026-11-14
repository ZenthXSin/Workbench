package io.github.zenthxsin.rustwright.core.ai

/** 锈匠 / RustPic 共用的 New-API 中转站（地址见 Endpoints，不明文存放）。 */
object Relay {
    val DEFAULT_BASE_URL: String get() = io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.main() + "/v1"
    val WALLET_URL: String get() = io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.main() + "/wallet"
    const val DISPLAY_HOST = "ai.铁壳锈世纪.com"
}

/** 对话补全协议族。 */
enum class ChatProtocol { OPENAI, ANTHROPIC }

/**
 * 一条可用的 AI 线路：服务地址 + 令牌 + 选定的对话 / 生图模型。
 * 对话与生图允许走不同地址（RustPic 的 chatBaseUrl / imageBaseUrl）。
 */
data class AiChannel(
    val baseUrl: String = Relay.DEFAULT_BASE_URL,
    val token: String,
    val chatModel: String,
    val imageModel: String? = null,
    val imageBaseUrl: String = baseUrl,
) {
    val chatProtocol: ChatProtocol get() = ModelRules.protocolFor(chatModel)
}
