package io.github.zenthxsin.rustwright.core.runtime

import io.github.zenthxsin.rustwright.core.runtime.net.Endpoints

/** Attribution remains visible when a consuming product supplies its own branding. */
object WorkbenchLicense {
    const val PROJECT = "Workbench"
    const val ORIGIN = "https://github.com/ZenthXSin/Workbench"
    const val COPYRIGHT = "Copyright (c) 2026 ZenthXSin and Workbench contributors"
    const val LICENSE = "AGPL-3.0-only"
    val source: String get() = Endpoints.configured("source.url", ORIGIN)
    fun text(): String = checkNotNull(Res.open("licenses/workbench/AGPL-3.0.txt"))
        .bufferedReader().use { it.readText() }
}
