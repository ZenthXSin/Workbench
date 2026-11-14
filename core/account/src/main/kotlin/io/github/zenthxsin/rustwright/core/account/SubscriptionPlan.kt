package io.github.zenthxsin.rustwright.core.account

import java.math.BigDecimal

enum class SubscriptionPlan(
    val id: String,
    val displayName: String,
    val monthlyPrice: BigDecimal,
) {
    BASIC("basic", "基础版", BigDecimal.ZERO),
    CREATOR("creator", "创作版", BigDecimal("9.90")),
    PRO("pro", "专业版", BigDecimal("19.99")),
    ;

    fun includes(required: SubscriptionPlan): Boolean = ordinal >= required.ordinal

    companion object {
        fun fromId(id: String?): SubscriptionPlan =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: BASIC
    }
}

/** Feature gates stay in one place so UI and Agent entry points can share the same rules. */
enum class PaidFeature(val minimumPlan: SubscriptionPlan) {
    REFERENCE_ANALYSIS(SubscriptionPlan.CREATOR),
    SVG_CREATION(SubscriptionPlan.CREATOR),
    IMAGE_PROCESSING(SubscriptionPlan.CREATOR),
    CUSTOM_SKILLS(SubscriptionPlan.CREATOR),
    MULTI_IMAGE_GENERATION(SubscriptionPlan.CREATOR),
    AGENT_PIXEL_DOCUMENTS(SubscriptionPlan.PRO),
    LIVE_CANVAS(SubscriptionPlan.PRO),
    FRAME_ANIMATION(SubscriptionPlan.PRO),
    STORYBOARD_BATCH_GENERATION(SubscriptionPlan.PRO),
    SCRIPT_EXTENSIONS(SubscriptionPlan.PRO),
    WORKSPACE_MEMORY(SubscriptionPlan.PRO),
    ;

    fun availableFor(plan: SubscriptionPlan): Boolean = plan.includes(minimumPlan)
}
