package io.github.zenthxsin.rustwright.core.ai.model

import io.github.zenthxsin.rustwright.core.ai.ChatProtocol
import io.github.zenthxsin.rustwright.core.ai.ModelRules

/**
 * 中转站 `GET /models` 的一条模型（合并锈匠 RelayModelEntry 与 RustPic 的分类规则）。
 * 倍率、上下文、最大输出等均可能缺失，为 null / 0 时由调用方回退到本地模板。
 */
data class ModelEntry(
    val id: String,
    /** 输入倍率（model_ratio）。 */
    val ratio: Double? = null,
    /** 输出倍率（completion_ratio）；缺失时等于输入倍率。 */
    val outputRatio: Double? = null,
    val contextWindow: Int = 0,
    val maxOutputTokens: Int = 0,
    /** New-API `supported_endpoint_types`。 */
    val supportedEndpointTypes: List<String> = emptyList(),
    /** `input_modalities`（含 image 即多模态）。 */
    val inputModalities: List<String> = emptyList(),
    /** New-API `quota_type`：0 按 token 倍率，1 按次 `model_price`。 */
    val quotaType: Int? = null,
    val modelPrice: Double? = null,
    val cacheRatio: Double? = null,
) {
    val kind: ModelKind get() = ModelRules.kindOf(this)
    val protocol: ChatProtocol get() = ModelRules.protocolFor(id)
    val multimodal: Boolean get() = ModelRules.isMultimodal(this)
    val pricedPerCall: Boolean get() = quotaType == 1
}

enum class ModelKind { CHAT, IMAGE, EMBEDDING, OTHER }
