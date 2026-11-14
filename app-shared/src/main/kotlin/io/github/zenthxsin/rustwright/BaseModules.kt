package io.github.zenthxsin.rustwright

import io.github.zenthxsin.rustwright.core.runtime.RwModule
import io.github.zenthxsin.rustwright.linux.impl.LinuxModule

/** Standalone workbench, with no business plugin on its compile or runtime classpath. */
object BaseModules {
    fun all(updateUrl: String = ""): List<RwModule> = listOf(
        HomeModule(updateUrl), AgentModule, WorkspaceModule, LinuxModule, CodexModule, FusedModule,
    )
}
