package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.account.NewApiBenefits

/** 免费对话沿用本地首句标题，避免额外标题请求使用全局付费模型。 */
internal suspend fun conversationTitle(model: String, prompt: String, remote: suspend (String) -> String?): String? =
    if (model.startsWith(NewApiBenefits.MODEL_PREFIX)) null else remote(prompt)
