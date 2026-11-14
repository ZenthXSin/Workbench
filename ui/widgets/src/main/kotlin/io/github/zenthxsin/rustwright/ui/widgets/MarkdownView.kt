package io.github.zenthxsin.rustwright.ui.widgets

import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import arc.graphics.Color
import arc.graphics.g2d.Font
import arc.graphics.g2d.Shapes
import arc.scene.style.Drawable
import arc.scene.ui.Label
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
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
 * Markdown 渲染：commonmark AST → Arc Table/Label。流式时整段重解析。
 * 外观由 [Style] 决定；默认是旧主题，融合版对话传入原型的 16/27 正文样式。
 */
class MarkdownView(private val md: Style = Style()) : Table() {

    /** 渲染样式。字体为 null 时退回旧主题。 */
    class Style(
        /** 正文（需开启颜色标记，见 RwTheme.richText），null 用旧主题字体且不上色。 */
        val body: Font? = null,
        val heading: Font? = null,
        val code: Font? = null,
        val text: Color = RwTheme.Colors.text,
        val muted: Color = RwTheme.Colors.muted,
        /** 行内代码 / 加粗的颜色。 */
        val codeInline: Color = RwTheme.Colors.accent,
        val strong: Color = RwTheme.Colors.text,
        val codeText: Color = RwTheme.Colors.codeText,
        val codeBg: Drawable? = null,
        val quoteBar: Color = RwTheme.Colors.surface2,
        val rule: Color = RwTheme.Colors.surface2,
        /** 段落间距（dp）。 */
        val gap: Float = 5f,
        val itemGap: Float = 0f,
        /** 链接文字颜色。 */
        val link: Color = RwTheme.Colors.accent,
    )

    /** 点了含链接的段落（取段内第一个链接的地址）；为 null 时链接只上色不可点。 */
    var onLink: ((String) -> Unit)? = null

    /** inline() 收集到的链接，下一次 label() 取走。 */
    private val pendingLinks = ArrayList<String>()

    var lastParseMs = 0f
        private set

    private val rich get() = md.body?.data?.markupEnabled == true

    fun setMarkdown(md: String) {
        val t0 = System.nanoTime()
        val doc = parser.parse(md)
        clearChildren()
        top().left()
        var n = doc.firstChild
        while (n != null) {
            block(this, n, 0, n.next == null)
            n = n.next
        }
        lastParseMs = (System.nanoTime() - t0) / 1e6f
    }

    private fun block(into: Table, node: Node, depth: Int, last: Boolean) {
        val gap = if (last) 0f else Scl.scl(md.gap)
        when (node) {
            is Heading -> {
                val l = md.heading?.let { Label(inline(node), Label.LabelStyle(it, md.text)) } ?: Label(inline(node), RwTheme.Styles.title)
                l.setWrap(true)
                into.add(l).growX().padTop(if (into.cells.isEmpty) 0f else Scl.scl(md.gap * 0.6f)).padBottom(gap).row()
            }
            is Paragraph -> into.add(label(inline(node))).growX().padBottom(gap).row()
            is FencedCodeBlock, is IndentedCodeBlock -> {
                val code = if (node is FencedCodeBlock) node.literal else (node as IndentedCodeBlock).literal
                val bg = md.codeBg ?: RwTheme.card(RwTheme.Colors.codeBg, RwTheme.Colors.outline, 8)
                val font = md.code ?: RwTheme.mono
                val box = Table(bg).apply {
                    margin(Scl.scl(if (md.code != null) 12f else 8f), Scl.scl(if (md.code != null) 14f else 8f), Scl.scl(if (md.code != null) 12f else 8f), Scl.scl(if (md.code != null) 14f else 8f))
                    add(Label(code.trimEnd(), Label.LabelStyle(font, md.codeText)).apply { setWrap(true); if (md.code == null) setFontScale(0.88f) }).growX()
                }
                into.add(box).growX().padBottom(gap).row()
            }
            is BulletList, is OrderedList -> {
                var i = (node as? OrderedList)?.markerStartNumber ?: 1
                var item = node.firstChild
                while (item != null) {
                    val marker = if (node is OrderedList) "${i++}." else BULLET
                    val row = Table()
                    val m = if (md.body != null) Label(marker, Label.LabelStyle(md.body, md.muted)) else label(marker, RwTheme.Colors.accent)
                    row.add(m).top().minWidth(Scl.scl(if (node is OrderedList) 20f else 14f)).padRight(Scl.scl(if (md.body != null) 6f else 5f))
                    val body = Table().top().left()
                    var c = (item as ListItem).firstChild
                    while (c != null) { block(body, c, depth + 1, c.next == null); c = c.next }
                    row.add(body).growX()
                    val lastItem = item.next == null
                    into.add(row).growX().padLeft(Scl.scl(if (md.body != null) 2f else 6f * depth))
                        .padBottom(if (lastItem) gap else Scl.scl(md.itemGap)).row()
                    item = item.next
                }
            }
            is BlockQuote -> {
                val q = if (md.body != null) object : Table() {
                    override fun drawBackground(x: Float, y: Float) {
                        Shapes.rect(x, y, Scl.scl(3f), height, Scl.scl(1.5f), Color(md.quoteBar).a(parentAlpha))
                    }
                }.apply { margin(0f, Scl.scl(14f), 0f, 0f) } else Table(RwTheme.solid(RwTheme.Colors.surface2)).apply { margin(Scl.scl(6f)) }
                var c = node.firstChild
                while (c != null) { block(q, c, depth, c.next == null); c = c.next }
                into.add(q).growX().padBottom(gap).row()
            }
            is TableBlock -> {
                val grid = Table(md.codeBg ?: RwTheme.solid(RwTheme.Colors.surface2))
                if (md.body != null) grid.margin(Scl.scl(6f))
                var section = node.firstChild
                while (section != null) {
                    var row = section.firstChild
                    while (row != null) {
                        if (row is TableRow) {
                            var cell = row.firstChild
                            while (cell != null) {
                                val head = (cell as TableCell).isHeader
                                val color = if (md.body != null) (if (head) md.text else md.muted) else if (head) RwTheme.Colors.accent else RwTheme.Colors.text
                                grid.add(label(inline(cell), color)).growX().uniformX().minWidth(0f).top().pad(Scl.scl(if (md.body != null) 6f else 3f))
                                cell = cell.next
                            }
                            grid.row()
                        }
                        row = row.next
                    }
                    section = section.next
                }
                into.add(grid).growX().padBottom(gap).row()
            }
            is ThematicBreak -> into.add(Table(RwTheme.solid(md.rule))).growX().height(Scl.scl(1f)).pad(Scl.scl(md.gap), 0f, Scl.scl(md.gap), 0f).row()
            else -> into.add(label(inline(node))).growX().padBottom(gap).row()
        }
    }

    private fun inline(node: Node): String {
        val sb = StringBuilder()
        val rich = rich
        fun esc(s: String) = if (rich) s.replace("[", "[[") else s
        fun colored(c: Color, s: String) = if (rich) "[#${c}]${esc(s)}[]" else s
        fun walk(n: Node) {
            when (n) {
                is Text -> sb.append(esc(n.literal))
                is Code -> sb.append(if (rich) colored(md.codeInline, n.literal) else "「${n.literal}」")
                is StrongEmphasis -> if (rich) {
                    sb.append("[#${md.strong}]")
                    var c = n.firstChild; while (c != null) { walk(c); c = c.next }
                    sb.append("[]")
                } else { var c = n.firstChild; while (c != null) { walk(c); c = c.next } }
                is Link -> {
                    pendingLinks += n.destination
                    if (rich) sb.append("[#${md.link}]")
                    var c = n.firstChild; while (c != null) { walk(c); c = c.next }
                    if (rich) sb.append("[]")
                }
                is SoftLineBreak -> sb.append(" ")
                is HardLineBreak -> sb.append('\n')
                else -> { var c = n.firstChild; while (c != null) { walk(c); c = c.next } }
            }
        }
        walk(node)
        return sb.toString()
    }

    private fun label(text: String, color: Color = md.text): Label {
        val l = (if (md.body != null) Label(text, Label.LabelStyle(md.body, color)) else Label(text).apply { setColor(color) }).apply { setWrap(true) }
        val url = pendingLinks.firstOrNull()
        pendingLinks.clear()
        val cb = onLink
        if (url != null && cb != null) {
            l.touchable = arc.scene.event.Touchable.enabled
            l.clicked { cb(url) }
        }
        return l
    }

    companion object {
        /** 有的字体没有「•」，退回「·」或「-」。 */
        private val BULLET: String by lazy {
            listOf('•', '·', '-').first { ch -> RwTheme.font.data.getGlyph(ch).let { it != null && it !== RwTheme.font.data.missingGlyph } }.toString()
        }
        private val parser: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
    }
}
