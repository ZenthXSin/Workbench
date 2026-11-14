package io.github.zenthxsin.rustwright.ui.codex

import arc.graphics.Color
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.Lucide
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * 助手回复的 Markdown（网页 .chatgpt-assistant-message .zcode-markdown）：
 * 正文 15px / 行高 1.75，粗体 600，行内代码 0.875em 等宽浅底圆角，h2-h4 17px，
 * 表格表头 600、单元格 padding 12px 12px 12px 0 + 底边线，表格下方复制 / 下载按钮，代码块 1px 边框圆角 10。
 */
internal class CodexMarkdown(private val onCopy: (String) -> Unit) : Table() {

    private val body get() = RwTheme.text(15f, 400, 26.25f)
    private val strong get() = RwTheme.text(15f, 600, 26.25f)
    private val code get() = RwTheme.monoAt(13.125f)
    private val chip get() = RwTheme.round(7, if (RwTheme.dark) Color(1f, 1f, 1f, 0.08f) else Color.valueOf("f3f3f3"))

    fun setMarkdown(md: String) {
        clearChildren()
        top().left()
        var n = parser.parse(md).firstChild
        var first = true
        while (n != null) {
            block(this, n, first)
            first = false
            n = n.next
        }
    }

    private fun block(into: Table, node: Node, first: Boolean) {
        val gap = if (first) 0f else dp(16f)
        when (node) {
            is Paragraph -> into.add(rich(node, 15f, 26.25f)).growX().padTop(gap).row()
            is Heading -> into.add(rich(node, 17f, 25.5f, weight = 600)).growX().padTop(if (first) 0f else dp(20f)).row()
            is BulletList, is OrderedList -> {
                val list = Table().top().left()
                var i = (node as? OrderedList)?.markerStartNumber ?: 1
                var item = node.firstChild
                while (item != null) {
                    val marker = if (node is OrderedList) "${i++}." else "•"
                    list.add(Css.label(marker, 15f, Css.text, 400, 26.25f)).top().width(dp(24f)).left()
                    val content = Table().top().left()
                    var c = (item as? ListItem)?.firstChild
                    var firstChild = true
                    while (c != null) { block(content, c, firstChild); firstChild = false; c = c.next }
                    list.add(content).growX().padTop(dp(2f)).row()
                    item = item.next
                }
                into.add(list).growX().padTop(gap).row()
            }
            is FencedCodeBlock, is IndentedCodeBlock -> {
                val literal = (node as? FencedCodeBlock)?.literal ?: (node as IndentedCodeBlock).literal
                val lang = (node as? FencedCodeBlock)?.info?.takeIf { it.isNotBlank() } ?: "text"
                into.add(codeBlock(literal.trimEnd(), lang)).growX().padTop(gap).row()
            }
            is BlockQuote -> {
                val quote = Table().top().left()
                var c = node.firstChild
                var firstChild = true
                while (c != null) { block(quote, c, firstChild); firstChild = false; c = c.next }
                into.add(Table().left().apply {
                    add(Css.rule(Css.border)).width(dp(3f)).growY()
                    add(quote).growX().padLeft(dp(12f))
                }).growX().padTop(gap).row()
            }
            is TableBlock -> into.add(table(node)).growX().padTop(if (first) 0f else dp(20f)).row()
            is ThematicBreak -> into.add(Css.rule()).growX().height(dp(1f)).padTop(dp(24f)).padBottom(dp(8f)).row()
            else -> into.add(Css.para(plain(node), 15f, Css.text, 400, 26.25f)).growX().padTop(gap).row()
        }
    }

    private fun rich(node: Node, px: Float, lh: Float, weight: Int = 400): RichText {
        val runs = ArrayList<RichText.Run>()
        val base = RwTheme.text(px, weight, lh)
        val bold = RwTheme.text(px, 600, lh)
        fun walk(n: Node, isBold: Boolean, link: Boolean) {
            when (n) {
                is Text -> runs += RichText.Run(n.literal, if (isBold) bold else base, if (link) RwTheme.Colors.info else Css.text)
                is Code -> runs += RichText.Run(n.literal, code, Css.text, chip)
                is SoftLineBreak -> runs += RichText.Run(" ", base, Css.text)
                is HardLineBreak -> runs += RichText.Run("\n", base, Css.text)
                else -> {
                    var c = n.firstChild
                    while (c != null) { walk(c, isBold || n is StrongEmphasis, link || n is Link); c = c.next }
                    if (n is Emphasis) Unit
                }
            }
        }
        walk(node, false, false)
        return RichText(runs, lh)
    }

    private fun plain(node: Node): String = buildString {
        fun walk(n: Node) {
            if (n is Text) append(n.literal) else if (n is Code) append(n.literal)
            var c = n.firstChild
            while (c != null) { walk(c); c = c.next }
        }
        walk(node)
    }

    /** 表格：表头 600，行底边线，下方右侧复制 / 下载（28px 按钮、14px 图标）。 */
    private fun table(node: TableBlock): Table {
        val rows = ArrayList<List<Pair<Node, Boolean>>>()
        var section = node.firstChild
        while (section != null) {
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    val cells = ArrayList<Pair<Node, Boolean>>()
                    var cell = row.firstChild
                    while (cell != null) { if (cell is TableCell) cells += cell to (section is TableHead); cell = cell.next }
                    rows += cells
                }
                row = row.next
            }
            section = section.next
        }
        val grid = Table().top().left()
        rows.forEachIndexed { r, cells ->
            cells.forEachIndexed { i, (cell, head) ->
                val last = i == cells.lastIndex
                grid.add(Css.label(plain(cell), 15f, Css.text, if (head) 600 else 400, 24f)).growX().uniformX().left()
                    .pad(dp(12f), if (i == 0) 0f else dp(12f), dp(12f), if (last) 0f else dp(12f))
            }
            grid.row()
            grid.add(Css.rule()).colspan(cells.size).growX().height(dp(1f)).row()
            if (r == rows.lastIndex) Unit
        }
        val markdown = rows.joinToString("\n") { cells -> cells.joinToString(" | ", "| ", " |") { plain(it.first) } }
        return Table().top().left().apply {
            add(grid).growX().row()
            add(Table().right().apply {
                add(Css.iconButton(Lucide.copy, 14f, Css.muted, 10) { onCopy(markdown) }).size(dp(28f))
                add(Css.iconButton(Lucide.download, 14f, Css.muted, 10) { onCopy(markdown) }).size(dp(28f)).padLeft(dp(4f))
            }).right().padTop(dp(4f))
        }
    }

    /** 代码块：1px 边框圆角 10，顶栏语言名 + 复制，内容等宽 13px 横向滚动。 */
    private fun codeBlock(text: String, lang: String) = Table(RwTheme.card(RwTheme.Colors.codeBg, Css.border, 10)).top().left().apply {
        add(Table().left().apply {
            margin(dp(6f), dp(12f), dp(6f), dp(6f))
            add(Css.label(lang, 12f, Css.muted)).growX().left()
            add(Css.iconButton(Lucide.copy, 14f, Css.muted, 10) { onCopy(text) }).size(dp(28f))
        }).growX().row()
        add(ScrollPane(Css.label(text, 13f, Css.text).apply { style = arc.scene.ui.Label.LabelStyle(RwTheme.monoAt(13f, 20f), Css.text) })
            .apply { setScrollingDisabled(false, true); setFadeScrollBars(true); setOverscroll(false, false) })
            .growX().pad(0f, dp(12f), dp(12f), dp(12f))
    }

    companion object {
        private val parser: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
    }
}
