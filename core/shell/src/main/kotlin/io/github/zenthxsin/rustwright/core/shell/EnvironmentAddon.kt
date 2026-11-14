package io.github.zenthxsin.rustwright.core.shell

import io.github.zenthxsin.rustwright.core.runtime.Contribution
import io.github.zenthxsin.rustwright.core.runtime.ExtensionPoint

/** Optional setup fragment, executed inside the managed environment under the setup lock. */
class EnvironmentAddon(
    override val id: String,
    val marker: String,
    val script: () -> String,
    override val order: Int = 100,
) : Contribution {
    init { require(marker.startsWith("/etc/") && marker.matches(Regex("[a-zA-Z0-9/_.-]+"))) }
}

object EnvironmentPoints {
    val ADDONS = ExtensionPoint<EnvironmentAddon>("environment.setup.addons")
}
