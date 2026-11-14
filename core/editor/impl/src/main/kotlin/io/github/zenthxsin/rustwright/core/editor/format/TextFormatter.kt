package io.github.zenthxsin.rustwright.core.editor.format

/**
 * 编辑器「格式化」：保守、可撤销的文本规范化。
 *
 * - 所有文件：去掉行尾空白，统一换行风格为 `\n`（保留文末是否有换行）。
 * - INI：在非多行块内，把 `key:value` / `key = value` 规范成 `key: value`（或原分隔符 + 单空格）。
 * - 不碰注释、节头、指令行、三引号多行块内的行；不重排节/键。
 */
object TextFormatter {

    private val SECTION_HEADER = Regex("""^\s*\[[^]]*]\s*$""")
    private val PROPERTY_LINE = Regex("""^(\s*)([^:=\s#/][^:=]*?)(\s*)([:=])(\s*)(.*)$""")

    fun format(text: String, isIni: Boolean = true): String {
        if (text.isEmpty()) return text
        val endsWithNewline = text.endsWith("\n") || text.endsWith("\r\n")
        val rawLines = text.split('\n')
        // split 会保留末行空串当 endsWithNewline；统一先按 \n 拆，去掉每行可能残留的 \r
        val lines = rawLines.map { it.trimEnd('\r') }

        val out = ArrayList<String>(lines.size)
        var inTripleQuote = false
        val lastIndex = lines.lastIndex

        lines.forEachIndexed { index, raw ->
            // 文末因 split 产生的空行占位：后面再决定是否补换行
            if (endsWithNewline && index == lastIndex && raw.isEmpty()) {
                return@forEachIndexed
            }

            var line = raw.trimEnd()
            if (isIni && !inTripleQuote) {
                line = formatIniLine(line)
            }
            out.add(line)

            if (isIni) {
                inTripleQuote = updateTripleQuoteState(inTripleQuote, raw)
            }
        }

        val joined = out.joinToString("\n")
        return if (endsWithNewline) "$joined\n" else joined
    }

    private fun formatIniLine(line: String): String {
        if (line.isEmpty()) return line
        val trimmedStart = line.trimStart()
        if (trimmedStart.startsWith("#") || trimmedStart.startsWith("//")) return line
        if (trimmedStart.startsWith("@")) return line
        if (SECTION_HEADER.matches(line)) return line.trimEnd()

        val match = PROPERTY_LINE.matchEntire(line) ?: return line
        val indent = match.groupValues[1]
        val key = match.groupValues[2].trimEnd()
        val sep = match.groupValues[4]
        val value = match.groupValues[6]
        if (key.isEmpty()) return line
        // 值侧只去掉「分隔符后多余前导空白」，保留值内有意空格；行尾已在外层 trimEnd
        return "$indent$key$sep ${value.trimStart()}"
    }

    /** 与高亮侧一致：数 `"""` 出现次数切换多行状态（奇数切换）。 */
    private fun updateTripleQuoteState(inTriple: Boolean, line: String): Boolean {
        var state = inTriple
        var i = 0
        while (i <= line.length - 3) {
            if (line[i] == '"' && line[i + 1] == '"' && line[i + 2] == '"') {
                state = !state
                i += 3
            } else {
                i++
            }
        }
        return state
    }
}
