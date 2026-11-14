package io.github.zenthxsin.rustwright.core.editor.search

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * 编辑器查找替换（普通 / 全词 / 正则）：普通 / 全词 / 正则三种，忽略大小写可选；
 * 正则按多行模式（`^` `$` 匹配每行首尾），替换串支持 `$n` 捕获组。
 */

object TextSearch {
    /** Legacy workspace replacement case rules; regex replacements retain their capture-group semantics. */
    fun preserveCase(original: String, replacement: String): String = when {
        // 原文里没有字母（数字、符号）就看不出大小写，原样替换：否则 "100" → "maxHp" 会被写成 "MAXHP"
        original.none { it.isLetter() } -> replacement
        original.all { it.isUpperCase() || !it.isLetter() } -> replacement.uppercase()
        original.all { it.isLowerCase() || !it.isLetter() } -> replacement.lowercase()
        original.first().isUpperCase() && original.drop(1).all { !it.isLetter() || it.isLowerCase() } ->
            replacement.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        else -> replacement
    }

    /** 编译查找串；正则写错时抛 [PatternSyntaxException]。 */
    fun compile(query: String, o: SearchOptions): Pattern {
        val flags = Pattern.MULTILINE or (if (o.ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0)
        val body = when {
            o.regex -> query
            o.wholeWord -> "(?<![\\w])" + Pattern.quote(query) + "(?![\\w])"
            else -> Pattern.quote(query)
        }
        return Pattern.compile(body, flags)
    }

    /** 所有匹配区间（空匹配跳过）。 */
    fun findAll(text: CharSequence, query: String, o: SearchOptions, limit: Int = 10_000): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val m = compile(query, o).matcher(text)
        val out = ArrayList<IntRange>()
        while (m.find() && out.size < limit) if (m.end() > m.start()) out += m.start() until m.end()
        return out
    }

    /** 第 [match] 个匹配替换成 [replacement]（正则时展开 `$n`），返回新文字和替换后文字的结束位置。 */
    fun replaceOne(text: String, query: String, o: SearchOptions, match: IntRange, replacement: String): Pair<String, Int> {
        val rep = expand(text, query, o, match, replacement)
        return (text.substring(0, match.first) + rep + text.substring(match.last + 1)) to (match.first + rep.length)
    }

    /** 全部替换，返回新文字和替换次数。 */
    fun replaceAll(text: String, query: String, o: SearchOptions, replacement: String): Pair<String, Int> {
        if (query.isEmpty()) return text to 0
        val m = compile(query, o).matcher(text)
        val sb = StringBuffer()
        var n = 0
        while (m.find()) {
            if (m.end() == m.start()) continue
            m.appendReplacement(sb, if (o.regex) replacement else Matcher.quoteReplacement(replacement))
            n++
        }
        m.appendTail(sb)
        return sb.toString() to n
    }

    /** 第 [match] 个匹配要换成的文字（正则时展开 `$n`）。 */
    fun expand(text: String, query: String, o: SearchOptions, match: IntRange, replacement: String): String {
        if (!o.regex) return replacement
        val m = compile(query, o).matcher(text)
        m.region(match.first, text.length)
        m.useAnchoringBounds(false)
        m.useTransparentBounds(true)
        if (!m.find() || m.start() != match.first) return replacement
        val sb = StringBuffer()
        m.appendReplacement(sb, replacement)
        return sb.substring(match.first)
    }
}

/** 通用正则模板。选中后写入查找（及可选替换）并开启正则。 */

val EditorRegexTemplates: List<EditorRegexTemplate> = listOf(
    EditorRegexTemplate("键 = 值 行", "匹配 key: value / key = value", """^(\s*)([A-Za-z_][\w.]*)\s*[:=]\s*(.*)$""", """$1$2: $3"""),
    EditorRegexTemplate("节头 [section]", "匹配 [settings] / [section]", """^\s*\[([^\]]+)\]\s*$"""),
    EditorRegexTemplate("数值（整数/小数）", "匹配数字字面量", """-?\d+(?:\.\d+)?"""),
    EditorRegexTemplate("true/false", "布尔字面量（忽略大小写时配合开关）", """\b(?:true|false)\b"""),
    EditorRegexTemplate("行尾注释 //", "匹配 // 到行尾", """//.*$""", ""),
    EditorRegexTemplate("尾随空格", "匹配行尾空白", """[ \t]+$""", ""),
    EditorRegexTemplate("捕获组示例", "查找 (foo)(bar)，替换 $2$1", """(\w+)(\s*[:=]\s*)(\S+)""", """$1$2$3"""),
)
