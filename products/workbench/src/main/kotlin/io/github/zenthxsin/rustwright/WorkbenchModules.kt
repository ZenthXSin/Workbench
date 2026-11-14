package io.github.zenthxsin.rustwright

import io.github.zenthxsin.rustwright.core.runtime.RwModule
import io.github.zenthxsin.rustwright.pic.impl.PicModule

/** Complete generic application, composed from an independent host and reusable plugins. */
object WorkbenchModules {
    fun all(updateUrl: String = ""): List<RwModule> = BaseModules.all(updateUrl) + listOf(PicModule, ImagePlugin)
}
