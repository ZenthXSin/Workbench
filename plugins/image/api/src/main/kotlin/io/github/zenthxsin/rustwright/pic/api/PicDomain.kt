package io.github.zenthxsin.rustwright.pic.api

import io.github.zenthxsin.rustwright.core.runtime.Contribution
import io.github.zenthxsin.rustwright.core.runtime.ExtensionPoint

/** Optional domain guidance; image implementations know nothing about business plugins. */
data class PicDomain(
    override val id: String,
    val scenes: List<String> = emptyList(),
    val generationGuidance: String = "",
    val instructions: String = "",
    override val order: Int = 0,
) : Contribution

object PicPoints { val DOMAINS = ExtensionPoint<PicDomain>("image.domains") }
