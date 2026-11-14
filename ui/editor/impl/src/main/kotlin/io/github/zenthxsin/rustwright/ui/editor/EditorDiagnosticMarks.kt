package io.github.zenthxsin.rustwright.ui.editor

import io.github.zenthxsin.rustwright.ui.editor.api.EditorDiagnostic
import io.github.zenthxsin.rustwright.ui.widgets.code.CodeEditor

/** Translates language diagnostics into visible text ranges. */
internal object EditorDiagnosticMarks {
    /**
     * 问题落到具体的字：给了列就从那一列标到这个词结束；给了键就标键（说的是值时标值）；都没有就标整行（去掉缩进）。
     */
    fun of(p: EditorDiagnostic, lines: List<String>): CodeEditor.Mark? {
        val l = p.line - 1
        val t = lines.getOrNull(l) ?: return null
        val level = if (p.severity == "error") 2 else 1
        val lead = t.takeWhile { it == ' ' || it == '\t' }.length
        val end = t.trimEnd().length
        if (end <= lead) return CodeEditor.Mark(l, 0, 1, level)
        val sep = t.indexOfFirst { it == ':' || it == '=' }
        val key = p.key?.takeIf { it.isNotBlank() }?.let { k -> t.indexOf(k).takeIf { it >= 0 }?.let { it to it + k.length } }
        val value = if (sep >= 0) {
            var a = sep + 1
            while (a < end && t[a] == ' ') a++
            if (a < end) a to end else null
        } else null
        val aboutValue = p.message.contains("值") || p.code.contains("value", true) || p.code.contains("type", true)
        val (a, b) = when {
            p.column > 1 && p.column - 1 < end -> {
                val s0 = p.column - 1
                var e0 = s0
                while (e0 < end && !t[e0].isWhitespace()) e0++
                s0 to maxOf(e0, s0 + 1)
            }
            key != null && aboutValue && value != null -> value
            key != null -> key
            value != null && aboutValue -> value
            else -> lead to end
        }
        return CodeEditor.Mark(l, a, b, level)
    }

}
