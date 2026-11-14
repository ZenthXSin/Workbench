package io.github.zenthxsin.rustwright.core.runtime

/** Supplied by the product, with usable defaults for the generic workbench. */
data class AppIdentity(
    val name: String = "Workbench",
    val assistant: String = "助手",
    val description: String = "通用软件工作台",
    val projectType: String = "directory",
    val instructions: String = "You are a software assistant in a general-purpose project workbench.",
    val markResource: String? = null,
)

/** Current UI identity; runtime services continue to use their injected identity. */
object AppBrand { var identity = AppIdentity() }
