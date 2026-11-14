package io.github.zenthxsin.rustwright.core.ai

import io.github.zenthxsin.rustwright.core.ai.model.ModelEntry
import io.github.zenthxsin.rustwright.core.ai.model.ModelKind

/**
 * 模型分类规则：优先看网关 `supported_endpoint_types`，没有时按 id 关键词兜底。
 * 合并自锈匠 RelayModelCatalogFilter 与 RustPic ModelCapabilities。
 */
object ModelRules {
    private val CHAT_ENDPOINT_TYPES = setOf("openai", "openai-response", "anthropic", "gemini", "azure-openai", "openrouter")
    private val NON_CHAT_ENDPOINT_TYPES = setOf(
        "image-generation", "video-generation", "openai-video", "3d-generation", "embeddings", "embedding",
        "jina-rerank", "rerank", "audio", "tts", "speech", "moderation",
    )

    fun kindOf(entry: ModelEntry): ModelKind {
        val types = entry.supportedEndpointTypes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if ("image-generation" in types || looksLikeImageModel(entry.id)) return ModelKind.IMAGE
        if ("embeddings" in types || "embedding" in types || "embedding" in entry.id.lowercase()) return ModelKind.EMBEDDING
        if (types.isNotEmpty()) {
            if (types.none { it in CHAT_ENDPOINT_TYPES }) return ModelKind.OTHER
            if (types.any { it in NON_CHAT_ENDPOINT_TYPES } && types.none { it in CHAT_ENDPOINT_TYPES }) return ModelKind.OTHER
        }
        return if (looksLikeNonChat(entry.id)) ModelKind.OTHER else ModelKind.CHAT
    }

    fun looksLikeImageModel(id: String): Boolean {
        val s = id.trim().lowercase()
        if (s.isEmpty() || "video" in s) return false
        return "image" in s || "imagine" in s || "flux" in s || "u1" in s || s.startsWith("sensenova")
    }

    private fun looksLikeNonChat(id: String): Boolean {
        val s = id.trim().lowercase()
        return s.isEmpty() || "video" in s || "embedding" in s || "rerank" in s || "tts" in s || "whisper" in s
    }

    fun protocolFor(modelId: String): ChatProtocol =
        if (modelId.trim().startsWith("claude-", ignoreCase = true)) ChatProtocol.ANTHROPIC else ChatProtocol.OPENAI

    fun isMultimodal(entry: ModelEntry): Boolean {
        if (entry.inputModalities.isNotEmpty()) return entry.inputModalities.any { it.equals("image", ignoreCase = true) }
        return !entry.id.startsWith("deepseek", ignoreCase = true)
    }
}
