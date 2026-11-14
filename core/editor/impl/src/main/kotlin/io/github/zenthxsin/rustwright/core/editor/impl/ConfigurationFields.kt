package io.github.zenthxsin.rustwright.core.editor.impl

import io.github.zenthxsin.rustwright.core.editor.api.TextDocument
import io.github.zenthxsin.rustwright.ui.widgets.code.Pos

/** Configuration syntax and edits, independent of widgets and language plugins. */
class ConfigurationFields(
    private val document: TextDocument,
    private val replace: (Pos, Pos, String) -> Unit,
) {
    fun sectionAt(line: Int): String? {
        for (i in line.coerceAtMost(document.lineCount - 1) downTo 0) {
            val text = document.line(i).trim()
            if (text.startsWith("[") && text.contains("]")) return text.substring(1, text.indexOf(']'))
        }
        return null
    }

    fun keyAt(line: Int): String? {
        if (line !in 0 until document.lineCount) return null
        val text = document.line(line)
        val trimmed = text.trimStart()
        if (trimmed.startsWith("#") || trimmed.startsWith("[")) return null
        val separator = text.indexOfFirst { it == ':' || it == '=' }
        return if (separator > 0) text.substring(0, separator).trim().takeIf { it.isNotEmpty() } else null
    }

    /** Value range [start, end), excluding spaces after the separator and trailing whitespace. */
    fun valueSpan(line: Int): Pair<Int, Int>? {
        if (line !in 0 until document.lineCount) return null
        val text = document.line(line)
        val separator = text.indexOfFirst { it == ':' || it == '=' }
        if (separator < 0) return null
        var start = separator + 1
        while (start < text.length && text[start] == ' ') start++
        return start to maxOf(start, text.trimEnd().length)
    }

    fun setValue(line: Int, key: String, value: String) {
        if (keyAt(line) != key) return
        val (start, end) = valueSpan(line) ?: return
        val text = document.line(line)
        val prefix = if (start == end && (start == 0 || text[start - 1] != ' ')) " " else ""
        replace(Pos(line, start), Pos(line, end), prefix + value)
    }

    /** Update an existing field, or insert at the end of its section. */
    fun setField(section: String, key: String, value: String) {
        var inside = false
        var last = -1
        for (i in 0 until document.lineCount) {
            val text = document.line(i).trim()
            if (text.startsWith("[")) {
                if (inside) break
                inside = text.startsWith("[$section]")
                if (inside) last = i
                continue
            }
            if (!inside) continue
            if (text.isNotEmpty()) last = i
            if (keyAt(i) == key) {
                setValue(i, key, value)
                return
            }
        }
        if (last < 0) return
        val end = Pos(last, document.line(last).length)
        replace(end, end, "\n$key: $value")
    }

    fun appendSection(section: String) {
        val end = document.end()
        val prefix = if (document.line(end.line).isBlank()) "\n" else "\n\n"
        replace(end, end, "$prefix[$section]\n")
    }
}
