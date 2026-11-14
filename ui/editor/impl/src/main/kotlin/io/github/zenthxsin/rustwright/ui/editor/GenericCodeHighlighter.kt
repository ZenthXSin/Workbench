package io.github.zenthxsin.rustwright.ui.editor

import io.github.zenthxsin.rustwright.ui.widgets.code.LineHighlighter

internal object GenericCodeTokens {
    const val STRING = 20
    const val COMMENT = 21
    const val KEYWORD = 22
    const val NUMBER = 23
}

/** Incremental scanner with cross-line block comment state. */
object GenericCodeHighlighter : LineHighlighter {
    private val words = setOf(
        "if", "else", "for", "while", "return", "class", "interface", "object", "fun", "val", "var",
        "def", "function", "import", "package", "public", "private", "const", "let", "true", "false",
        "null", "None", "async", "await", "fn", "struct", "impl", "use", "match", "try", "catch",
    )

    override fun highlight(line: String, state: Int, out: ByteArray): Int {
        out.fill(0)
        var offset = 0
        var block = state == 1
        fun mark(start: Int, end: Int, token: Int) {
            for (index in start until end.coerceAtMost(line.length)) out[index] = token.toByte()
        }
        while (offset < line.length) {
            val start = offset
            when {
                block || line.startsWith("/*", offset) -> {
                    val end = line.indexOf("*/", if (block) offset else offset + 2)
                    if (end < 0) { mark(offset, line.length, GenericCodeTokens.COMMENT); return 1 }
                    offset = end + 2
                    mark(start, offset, GenericCodeTokens.COMMENT)
                    block = false
                }
                line.startsWith("//", offset) || line[offset] == '#' -> {
                    mark(offset, line.length, GenericCodeTokens.COMMENT)
                    break
                }
                line[offset] == '"' || line[offset] == '\'' || line[offset] == '`' -> {
                    offset = quotedEnd(line, offset)
                    mark(start, offset, GenericCodeTokens.STRING)
                }
                line[offset].isDigit() -> {
                    while (offset < line.length && (line[offset].isLetterOrDigit() || line[offset] == '.')) offset++
                    mark(start, offset, GenericCodeTokens.NUMBER)
                }
                line[offset].isLetter() || line[offset] == '_' -> {
                    while (offset < line.length && (line[offset].isLetterOrDigit() || line[offset] == '_')) offset++
                    if (line.substring(start, offset) in words) mark(start, offset, GenericCodeTokens.KEYWORD)
                }
                else -> offset++
            }
        }
        return 0
    }

    private fun quotedEnd(line: String, start: Int): Int {
        val quote = line[start]
        var offset = start + 1
        while (offset < line.length) {
            val character = line[offset++]
            if (character == '\\') { if (offset < line.length) offset++ }
            else if (character == quote) break
        }
        return offset
    }
}
