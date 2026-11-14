package io.github.zenthxsin.rustwright.ui.kit

/**
 * 命令块的简易语法高亮，输出 Arc 颜色标记（配合 RwTheme.richMono）。
 * 着色：命令名、字符串、参数（-x / --x）、变量（$X）、数字、注释、heredoc 内容。
 */
object Highlight {
    private const val CMD = "E5C07B"
    private const val STR = "98C379"
    private const val FLAG = "61AFEF"
    private const val VAR = "E06C75"
    private const val NUM = "D19A66"
    private const val COMMENT = "7F848E"
    private const val PLAIN = "D6D4CC"

    private fun esc(s: String) = s.replace("[", "[[")
    private fun c(color: String, s: String) = "[#$color]${esc(s)}[]"

    fun shell(src: String): String {
        // 整段扫描：引号字符串、heredoc 可以跨行
        val out = StringBuilder()
        var i = 0
        var expectCmd = true
        var heredoc: String? = null
        val n = src.length
        while (i < n) {
            val ch = src[i]
            when {
                ch == '\n' -> {
                    out.append('\n'); i++
                    expectCmd = true
                    val tag = heredoc
                    if (tag != null) {
                        // heredoc 正文直到单独一行的结束标记
                        val start = i
                        var end = n
                        var j = i
                        while (j <= n) {
                            val lineEnd = src.indexOf('\n', j).let { if (it < 0) n else it }
                            if (src.substring(j, lineEnd).trim() == tag) { end = lineEnd; break }
                            if (lineEnd >= n) { end = n; break }
                            j = lineEnd + 1
                        }
                        out.append(c(STR, src.substring(start, end)))
                        i = end
                        heredoc = null
                    }
                }
                ch == '#' && (i == 0 || src[i - 1].isWhitespace()) -> {
                    val end = src.indexOf('\n', i).let { if (it < 0) n else it }
                    out.append(c(COMMENT, src.substring(i, end))); i = end
                }
                ch == '\'' || ch == '"' -> {
                    var j = i + 1
                    while (j < n && src[j] != ch) { if (src[j] == '\\' && ch == '"') j++; j++ }
                    val end = (j + 1).coerceAtMost(n)
                    out.append(c(STR, src.substring(i, end))); i = end; expectCmd = false
                }
                ch == '$' -> {
                    var j = i + 1
                    if (j < n && src[j] == '{') { while (j < n && src[j] != '}') j++; j++ }
                    else while (j < n && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                    val end = j.coerceAtMost(n)
                    out.append(c(VAR, src.substring(i, end))); i = end; expectCmd = false
                }
                ch == ' ' || ch == '\t' -> { out.append(ch); i++ }
                ch == ';' || ch == '|' || ch == '&' || ch == '(' || ch == ')' -> { out.append(c(PLAIN, ch.toString())); i++; if (ch != ')') expectCmd = true }
                else -> {
                    var j = i
                    while (j < n && !src[j].isWhitespace() && src[j] !in ";|&()'\"$") j++
                    if (j == i) j = i + 1
                    val word = src.substring(i, j)
                    when {
                        word.startsWith("<<") -> {
                            val tag = word.removePrefix("<<").removePrefix("-").trim('\'', '"')
                            heredoc = tag.ifEmpty { null }
                            out.append(c(PLAIN, word))
                        }
                        expectCmd && !word.contains('=') -> { out.append(c(CMD, word)); expectCmd = false }
                        word.startsWith("-") -> out.append(c(FLAG, word))
                        word.all { it.isDigit() || it == '.' } -> out.append(c(NUM, word))
                        else -> out.append(c(PLAIN, word))
                    }
                    i = j
                }
            }
        }
        return out.toString()
    }
}
