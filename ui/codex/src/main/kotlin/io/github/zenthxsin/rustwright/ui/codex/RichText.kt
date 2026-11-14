package io.github.zenthxsin.rustwright.ui.codex

import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Font
import arc.scene.Element
import arc.scene.style.Drawable
import io.github.zenthxsin.rustwright.ui.designsystem.dp

/**
 * 混排文字：一段里可以有不同字体 / 颜色 / 底色（粗体、行内代码、链接），按宽度自动换行。
 * 中日韩字符可在任意处断行，西文优先在空格处断。
 */
internal class RichText(private val runs: List<Run>, private val lineHeightPx: Float) : Element() {

    class Run(val text: String, val font: Font, val color: Color, val chip: Drawable? = null)

    private class Piece(val run: Run, val text: String, var x: Float, var line: Int, val width: Float)

    private val pieces = ArrayList<Piece>()
    private var lines = 1
    private var laidOutWidth = -1f
    private var naturalWidth = 0f
    private val chipPad = dp(4f)

    init {
        naturalWidth = runs.sumOf { r -> (measure(r.font, r.text) + if (r.chip != null) chipPad * 2 else 0f).toDouble() }.toFloat()
    }

    private fun advance(font: Font, c: Char): Float {
        val g = font.data.getGlyph(c) ?: return if (c == ' ') font.data.spaceXadvance else 0f
        return g.xadvance * font.data.scaleX
    }

    private fun measure(font: Font, s: String) = s.fold(0f) { acc, c -> acc + advance(font, c) }

    private fun breakable(c: Char) = c == ' ' || c.code > 0x2E80

    private fun relayout(maxWidth: Float) {
        pieces.clear()
        var lineX = 0f
        var line = 0
        for (run in runs) {
            val t = run.text
            val pad = if (run.chip != null) chipPad else 0f
            lineX += pad
            var segStart = 0
            var segX = lineX
            var segW = 0f
            var lastBreak = -1
            var wAtBreak = 0f
            fun emit(end: Int, w: Float) { if (end > segStart) pieces += Piece(run, t.substring(segStart, end), segX, line, w) }
            var i = 0
            while (i < t.length) {
                val c = t[i]
                if (c == '\n') {
                    emit(i, segW); line++; lineX = pad
                    i++; segStart = i; segX = lineX; segW = 0f; lastBreak = -1
                    continue
                }
                val a = advance(run.font, c)
                if (lineX + a > maxWidth && i > segStart) {
                    // 西文回退到最近的可断点，中文直接在当前字前断
                    val useBreak = lastBreak >= segStart && !breakable(c)
                    val cut = if (useBreak) lastBreak + 1 else i
                    emit(cut, if (useBreak) wAtBreak else segW)
                    line++; lineX = pad
                    i = cut
                    while (i < t.length && t[i] == ' ') i++
                    segStart = i; segX = lineX; segW = 0f; lastBreak = -1
                    continue
                }
                lineX += a; segW += a
                if (breakable(c)) { lastBreak = i; wAtBreak = segW }
                i++
            }
            emit(t.length, segW)
            lineX += pad
        }
        lines = line + 1
        laidOutWidth = maxWidth
    }

    override fun layout() {
        if (width != laidOutWidth) {
            val before = lines
            relayout(width)
            // 行数变了：通知父布局重新量高度，避免和下一块重叠
            if (lines != before) invalidateHierarchy()
        }
    }

    override fun getPrefWidth() = naturalWidth
    override fun getMinWidth() = 0f
    override fun getPrefHeight(): Float {
        if (width > 0 && width != laidOutWidth) relayout(width)
        return lines * dp(lineHeightPx)
    }

    override fun draw() {
        if (width != laidOutWidth) relayout(width)
        val lh = dp(lineHeightPx)
        for (p in pieces) {
            val baseY = y + height - p.line * lh
            p.run.chip?.let { chip ->
                Draw.color(Color.white, parentAlpha)
                val ch = p.run.font.lineHeight + dp(2f)
                chip.draw(x + p.x - chipPad, baseY - (lh + ch) / 2, p.width + chipPad * 2, ch)
            }
            val f = p.run.font
            f.color = Color(p.run.color).mul(1f, 1f, 1f, parentAlpha)
            // 文字在行内垂直居中
            val textY = baseY - (lh - f.capHeight) / 2
            f.draw(p.text, x + p.x, textY)
        }
        Draw.reset()
    }
}
