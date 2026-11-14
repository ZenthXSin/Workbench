package io.github.zenthxsin.rustwright.core.ai.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 一次 `GET /models` 的结果。 */
data class ModelCatalog(val entries: List<ModelEntry>) {
    val chat: List<ModelEntry> get() = entries.filter { it.kind == ModelKind.CHAT }
    val image: List<ModelEntry> get() = entries.filter { it.kind == ModelKind.IMAGE }
    val embedding: List<ModelEntry> get() = entries.filter { it.kind == ModelKind.EMBEDDING }

    private val byId = entries.associateBy { it.id.lowercase() }

    /** 严格 id 匹配（大小写不敏感）。 */
    operator fun get(id: String): ModelEntry? = byId[id.trim().lowercase()]

    fun preferredChat(preferred: String? = null): ModelEntry? = preferred?.let(::get)?.takeIf { it.kind == ModelKind.CHAT } ?: chat.firstOrNull()
    fun preferredImage(preferred: String? = null): ModelEntry? = preferred?.let(::get)?.takeIf { it.kind == ModelKind.IMAGE } ?: image.firstOrNull()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 解析 New-API / OpenAI / OpenRouter 形态的模型列表；无法解析时抛 [IllegalArgumentException]。 */
        fun parse(body: String): ModelCatalog {
            val root = runCatching { json.parseToJsonElement(body) }.getOrElse { throw IllegalArgumentException("模型列表不是合法 JSON", it) }
            val data = when (root) {
                is JsonObject -> root["data"] as? JsonArray ?: throw IllegalArgumentException("模型列表缺少 data")
                is JsonArray -> root
                else -> throw IllegalArgumentException("模型列表格式有误")
            }
            val entries = data.mapNotNull { (it as? JsonObject)?.let(::parseEntry) }.distinctBy { it.id.lowercase() }
            return ModelCatalog(entries)
        }

        fun parseEntry(item: JsonObject): ModelEntry? {
            val id = item.string("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val input = item.firstDouble("model_ratio", "ratio", "billing_ratio", "price_ratio")
            return ModelEntry(
                id = id,
                ratio = input,
                outputRatio = item.firstDouble("completion_ratio", "output_ratio", "completion_price_ratio", "output_price_ratio") ?: input,
                contextWindow = item.firstDouble("context_length", "context_window", "max_context", "max_context_tokens", "context")?.toInt() ?: 0,
                maxOutputTokens = item.firstDouble("max_tokens", "max_output_tokens", "max_completion_tokens", "completion_tokens", "output_token_limit")?.toInt() ?: 0,
                supportedEndpointTypes = item.strings("supported_endpoint_types"),
                inputModalities = item.strings("input_modalities").ifEmpty { (item["architecture"] as? JsonObject)?.strings("input_modalities").orEmpty() },
                quotaType = item.firstDouble("quota_type", "quotaType")?.toInt(),
                modelPrice = item.firstDouble("model_price", "modelPrice"),
                cacheRatio = item.firstDouble("cache_ratio", "cacheRatio"),
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.strings(key: String): List<String> =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()

        private fun JsonObject.firstDouble(vararg keys: String): Double? {
            for (k in keys) {
                val p = this[k] as? JsonPrimitive ?: continue
                (p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull())?.let { return it }
            }
            return null
        }
    }
}
