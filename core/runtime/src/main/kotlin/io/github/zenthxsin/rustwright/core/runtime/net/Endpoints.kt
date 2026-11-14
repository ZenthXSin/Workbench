package io.github.zenthxsin.rustwright.core.runtime.net

import java.net.URI

/** Deployment configuration. Reserved .invalid defaults never point at a live service. */
object Endpoints {
    fun configured(name: String, fallback: String): String =
        (System.getProperty("workbench.$name") ?: System.getenv("WORKBENCH_" + name.uppercase().replace('.', '_')))
            ?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    private fun address(name: String, fallback: String): String = configured(name, fallback).trimEnd('/').also {
        val u = URI(it)
        require(u.scheme == "https" && u.host != null && u.userInfo == null && u.query == null && u.fragment == null) {
            "workbench.$name must be an HTTPS address without credentials, query or fragment"
        }
    }

    fun main(): String = address("api.origin", "https://api.example.invalid")
    fun bgp(): String = address("api.alternate", main())
    fun mirror(): String = address("mirror.origin", "https://mirror.example.invalid")
    fun legacyMirror(): String = address("mirror.alternate", mirror())
    fun signer(): String = configured("android.signer", "").lowercase()
    fun relayOrigins(): List<String> = listOf(main(), bgp()).distinct()
    fun benefitModelPrefix(): String = configured("benefits.prefix", "workbench-free/")
    fun relayGroup(): String = configured("account.group", "workbench")
}
