package io.github.zenthxsin.rustwright.ui.editor

import io.github.zenthxsin.rustwright.ui.editor.api.EditorDiagnostic

/** Conservative duplicate-key checks for configuration files. */
internal object ConfigurationDiagnostics {
    fun check(text: String): List<EditorDiagnostic> {
        val seen = hashSetOf<String>()
        val result = arrayListOf<EditorDiagnostic>()
        var section = ""
        for ((index, raw) in text.lines().withIndex()) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.removeSurrounding("[", "]")
                continue
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("@")) continue
            val separator = line.indexOfFirst { it == ':' || it == '=' }
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim()
            if (!seen.add("$section|$key")) {
                result += EditorDiagnostic("warn", index + 1, "同一段落中重复定义 $key", section, key, code = "duplicate-key")
            }
        }
        return result
    }
}
