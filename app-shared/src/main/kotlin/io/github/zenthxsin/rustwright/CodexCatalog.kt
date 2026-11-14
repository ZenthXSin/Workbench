package io.github.zenthxsin.rustwright

import io.github.zenthxsin.rustwright.linux.impl.LinuxSetup

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

/** Keep code mode when its host can start; retain relay/MCP compatibility in both paths. */
internal fun codexCatalogPreparation(
    bin: String,
    catalog: String,
    host: String? = null,
    versionFile: String? = null,
): String {
    val raw = shellQuote("$catalog.raw")
    val temporary = shellQuote("$catalog.tmp")
    val destination = shellQuote(catalog)
    val locateHost = host?.let { "rw_host=${shellQuote(it)}" }
        ?: "rw_host=\$(dirname \"\$(readlink -f \"\$(command -v ${shellQuote(bin)})\")\")/codex-code-mode-host"
    val versionCheck = versionFile?.let {
        "[ \"\$(cat ${shellQuote(it)} 2>/dev/null)\" = ${shellQuote(LinuxSetup.CODEX_VERSION)} ] && "
    }.orEmpty()
    return "$locateHost; " +
        "rw_mode_filter='s/\"tool_mode\":\"code_mode_only\"/\"tool_mode\":null/g'; " +
        "if [ -x \"\$rw_host\" ] && $versionCheck timeout 3 \"\$rw_host\" --help >/dev/null 2>&1; then rw_mode_filter=''; fi; " +
        "if ${shellQuote(bin)} debug models --bundled > $raw 2>/dev/null; then " +
        "sed -e \"\$rw_mode_filter\" -e 's/\"use_responses_lite\":true/\"use_responses_lite\":false/g' " +
        "-e 's/\"supports_search_tool\":true/\"supports_search_tool\":false/g' $raw > $temporary " +
        "&& grep -q '\"models\"' $temporary && mv -f $temporary $destination " +
        "|| rm -f $temporary $destination; else rm -f $temporary $destination; fi; rm -f $raw; "
}
