package io.github.zenthxsin.rustwright.core.account

import org.json.JSONObject

/** 服务端福利余额；时间均为 Unix 秒，0 表示周期尚未开始。 */
data class BenefitWindow(
    val limit: Long,
    val used: Long,
    val reserved: Long,
    val remaining: Long,
    val startsAt: Long,
    val resetsAt: Long,
)

data class BenefitBalance(
    val fiveHours: BenefitWindow,
    val sevenDays: BenefitWindow,
    val generation: BenefitWindow,
    val dayTimezone: String,
    val generationCounts: Map<String, Long>,
    val pendingText: Long,
    val pendingGeneration: Long,
    val needsReview: Boolean,
)

data class NewApiBenefits(
    val enabled: Boolean,
    val eligible: Boolean = false,
    val eligibilityReason: String? = null,
    val textModels: List<String> = emptyList(),
    val imageModel: String? = null,
    val balance: BenefitBalance? = null,
    val serverTime: Long? = null,
) {
    /** 本地读取失败、服务未启用或资格未确认时都不能开放免费模型。 */
    val available: Boolean get() = enabled && eligible && balance != null && !balance.needsReview
    val textAvailable: Boolean get() = available && balance!!.fiveHours.remaining > 0 && balance.sevenDays.remaining > 0
    val generationAvailable: Boolean get() = available && balance!!.generation.remaining > 0

    companion object {
        val MODEL_PREFIX: String get() = io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.benefitModelPrefix()

        fun displayModel(id: String): String = id.removePrefix(MODEL_PREFIX)

        internal fun parse(data: JSONObject): NewApiBenefits {
            val enabled = data.get("enabled") as? Boolean ?: error("福利状态格式错误")
            if (!enabled) return NewApiBenefits(false)
            val eligible = data.get("eligible") as? Boolean ?: error("福利资格格式错误")
            val reason = data.optString("eligibility_reason").takeIf { it.isNotBlank() && it != "null" }
            val models = data.optJSONObject("models")
            val text = models?.optJSONArray("text")?.let { array ->
                (0 until array.length()).map { array.getString(it) }.distinct()
            }.orEmpty()
            val image = models?.optString("image")?.takeIf { it.isNotBlank() && it != "null" }
            require((text + listOfNotNull(image)).all { it.startsWith(MODEL_PREFIX) && it.length > MODEL_PREFIX.length }) { "福利模型格式错误" }
            val balance = data.optJSONObject("balance")?.let { b ->
                BenefitBalance(window(b.getJSONObject("five_hours")), window(b.getJSONObject("seven_days")), window(b.getJSONObject("generation")),
                    b.getString("day_timezone"), b.keys().asSequence().filter { it !in setOf("five_hours", "seven_days", "generation", "day_timezone", "pending_text", "pending_generation", "needs_review") }.associateWith { number(b, it) }, number(b, "pending_text"), number(b, "pending_generation"),
                    b.get("needs_review") as? Boolean ?: error("福利核对状态格式错误"))
            }
            require(!eligible || balance != null) { "服务端未返回福利余额" }
            return NewApiBenefits(enabled, eligible, reason, text, image, balance, if (data.has("server_time")) number(data, "server_time") else null)
        }

        private fun number(data: JSONObject, key: String): Long {
            val raw = data.get(key)
            val value = (raw as? Number)?.toString()?.toLongOrNull() ?: error("福利字段 $key 格式错误")
            require(value >= 0) { "福利字段 $key 不能为负数" }
            return value
        }

        private fun window(data: JSONObject): BenefitWindow = BenefitWindow(
            number(data, "limit"), number(data, "used"), number(data, "reserved"), number(data, "remaining"), number(data, "starts_at"), number(data, "resets_at"),
        ).also {
            require(it.remaining <= it.limit && (it.startsAt == 0L && it.resetsAt == 0L || it.startsAt > 0L && it.resetsAt > it.startsAt)) { "福利周期格式错误" }
        }
    }
}
