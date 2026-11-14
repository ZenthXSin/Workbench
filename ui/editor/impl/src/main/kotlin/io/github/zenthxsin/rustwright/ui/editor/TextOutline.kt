package io.github.zenthxsin.rustwright.ui.editor

import io.github.zenthxsin.rustwright.ui.editor.api.OutlineNode

object TextOutline {
    fun parse(lines: List<String>, config: Boolean): List<OutlineNode> {
        val out = arrayListOf<OutlineNode>(); var group: OutlineNode? = null; val children = arrayListOf<OutlineNode>()
        fun flush() { group?.let { out += it.copy(children = children.toList()) }; group = null; children.clear() }
        val declaration = Regex("(?:class|interface|object|enum|fun|def|function|struct|trait|fn)\\s+([A-Za-z_$][\\w$]*)")
        for ((i, raw) in lines.withIndex()) {
            val t = raw.trim()
            if (config && t.startsWith("[") && t.endsWith("]")) { flush(); group = OutlineNode(t, i); continue }
            if (config && !t.startsWith("#") && !t.startsWith(";") && !t.startsWith("@")) {
                val sep = t.indexOfFirst { it == ':' || it == '=' }
                if (sep > 0) { if (group == null) group = OutlineNode("文件", 0); children += OutlineNode(t.substring(0, sep).trim(), i, t.substring(sep + 1).trim()) }
            } else if (!config) {
                if (t.startsWith("#") && t.dropWhile { it == '#' }.startsWith(" ")) out += OutlineNode(t, i)
                else declaration.find(t)?.let { out += OutlineNode(it.value, i) }
                if (t.startsWith("\"") && ':' in t) out += OutlineNode(t.substringBefore(':').trim().trim('"'), i, t.substringAfter(':').trim())
            }
        }
        flush(); return out
    }
}
