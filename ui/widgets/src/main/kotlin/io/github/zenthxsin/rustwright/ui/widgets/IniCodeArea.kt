package io.github.zenthxsin.rustwright.ui.widgets

import arc.graphics.Color
import arc.graphics.g2d.Font
import arc.graphics.g2d.Draw
import arc.graphics.g2d.ScissorStack
import arc.math.Mat
import arc.scene.ui.TextArea
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import arc.util.Align

/**
 * 带 INI 语法高亮的多行编辑框（基于 Arc TextArea）：
 * 节名、键、数值、布尔、注释、@memory/@define 等指令、三引号块分色；
 * 放在 ScrollPane 里时只绘制裁剪区内的行，长文件滚动不卡。左侧显示逻辑行号（自动换行的续行不编号）。
 */
class IniCodeArea(text: String, private val lineNumbers: Boolean = true, private val background: Color? = null) : TextArea(text) {

    private var colored: CharSequence? = null
    private var kinds = ByteArray(0)
    private val run = Color()

    private var rows = -1f
    private var gutterDigits = 0
    private var gutterWidth = 0f
    private var numbered: CharSequence? = null
    private var lineNo = IntArray(0)
    private val numberColor get() = RwTheme.Colors.muted

    /** 问题标记：逻辑行号（1 基）→ 2 错误 / 1 警告；行号变色，行内容下画线。 */
    var lineMarks: Map<Int, Int> = emptyMap()

    /** 查找结果（字符区间，按位置排好）和当前项下标；在文字底下画底色。 */
    var matches: List<IntRange> = emptyList()
    var currentMatch: Int = -1

    /** 补全的幽灵后缀：画在光标后面的灰字（只是预览，不进文本）。 */
    var ghost: String? = null

    /** 光标所在逻辑行（0 基，按 '\n' 算，不管自动换行）。 */
    val logicalLine: Int get() { var n = 0; val t = text; val c = cursorPosition.coerceAtMost(t.length); for (i in 0 until c) if (t[i] == '\n') n++; return n }

    /** 光标在逻辑行里的列（0 基）。 */
    val logicalColumn: Int get() { val c = cursorPosition.coerceAtMost(text.length); return c - (text.lastIndexOf('\n', c - 1) + 1) }

    /** 第 [line] 个逻辑行的文字。 */
    fun logicalLineText(line: Int): String = text.split('\n').getOrElse(line) { "" }

    /** 光标左下角在本控件里的坐标（弹窗贴着它放）；x 是光标横向位置，y 是光标所在行的底边。 */
    fun cursorLocal(out: arc.math.geom.Vec2): arc.math.geom.Vec2 {
        val font = style.font
        val bg = style.background
        val offset = if (cursor >= glyphPositions.size || cursorLine * 2 >= linesBreak.size) 0f
            else glyphPositions.get(cursor) - glyphPositions.get(linesBreak.items[cursorLine * 2])
        val top = height - (bg?.topHeight ?: 0f)
        return out.set((bg?.leftWidth ?: 0f) + offset, top - (cursorLine - firstLineShowing + 1) * font.lineHeight)
    }

    init {
        // 行数变化时更新首选高度并重新布局，让外层 ScrollPane 负责滚动（setPrefRows 本身不会触发重新布局）
        update {
            val r = lines + 2f
            if (r != rows) { rows = r; setPrefRows(r); invalidateHierarchy() }
            if (lineNumbers) ensureGutter()
            keepCursorVisible(this)
        }
    }

    /**
     * 行号栏：把背景左内边距加宽，TextField 的文字、光标、选区、点击定位都会跟着右移；
     * 行号画在加宽出来的位置。位数变化（如 99 → 100 行）时重建样式。
     */
    private fun ensureGutter() {
        val digits = maxOf(2, (text.count { it == '\n' } + 1).toString().length)
        if (digits == gutterDigits) return
        gutterDigits = digits
        val font = style.font
        val digit = font.data.getGlyph('0')?.xadvance?.toFloat() ?: (font.lineHeight * 0.5f)
        gutterWidth = digits * digit + arc.scene.ui.layout.Scl.scl(8f)
        val st = TextFieldStyle(style)
        // 编辑器铺满卡片底色；左侧留出行号栏
        val pad = arc.scene.ui.layout.Scl.scl(6f)
        st.background = RwTheme.solid(background ?: RwTheme.Colors.surface).also {
            it.leftWidth = pad + gutterWidth; it.rightWidth = pad; it.topHeight = pad; it.bottomHeight = pad
        }
        st.focusedBackground = st.background
        style = st
    }

    /** 每个可视行对应的逻辑行号（续行为 0）。 */
    private fun lineNumbersFor(shown: CharSequence): IntArray {
        if (shown === numbered && lineNo.size == linesBreak.size / 2) return lineNo
        val n = linesBreak.size / 2
        val out = IntArray(n)
        var logical = 0
        for (v in 0 until n) {
            val start = linesBreak.items[v * 2]
            if (start == 0 || (start - 1 < shown.length && shown[start - 1] == '\n')) { logical++; out[v] = logical }
        }
        numbered = shown
        lineNo = out
        return out
    }

    /** 让外层 ScrollPane 滚到光标所在行（不要求有焦点，查找跳转用）。 */
    fun scrollToCursor() {
        layout()
        var p = parent
        while (p != null && p !is arc.scene.ui.ScrollPane) p = p.parent
        val pane = p as? arc.scene.ui.ScrollPane ?: return
        val lh = style.font.lineHeight
        val y = height - (cursorLine + 1) * lh - (style.background?.topHeight ?: 0f)
        pane.scrollTo(0f, y - lh * 2, 1f, lh * 5)
    }

    companion object {
        /** 光标所在行变化且有焦点时，让外层 ScrollPane 滚到光标处（输入、方向键移动时）。 */
        fun keepCursorVisible(area: TextArea) {
            val line = area.cursorLine
            if (area.userObject == line) return
            area.userObject = line
            if (arc.Core.scene?.keyboardFocus !== area) return
            var p = area.parent
            while (p != null && p !is arc.scene.ui.ScrollPane) p = p.parent
            val pane = p as? arc.scene.ui.ScrollPane ?: return
            val lh = area.style.font.lineHeight
            val y = area.height - (line + 1) * lh - (area.style.background?.topHeight ?: 0f)
            pane.scrollTo(0f, y - lh, 1f, lh * 3)
        }
    }

    override fun drawText(font: Font, x: Float, y: Float) {
        val shown = displayText
        if (shown !== colored || kinds.size != shown.length) {
            kinds = IniHighlighter.classify(shown)
            colored = shown
        }
        val had = font.data.markupEnabled
        font.data.markupEnabled = false
        val base = font.color.cpy()
        val lh = font.lineHeight
        val (top, bottom) = viewport()
        val numbers = if (lineNumbers && gutterWidth > 0f) lineNumbersFor(shown) else null
        var offsetY = 0f
        var i = firstLineShowing * 2
        while (i < (firstLineShowing + linesShowing) * 2 && i < linesBreak.size) {
            val lineY = y + offsetY
            if (lineY - lh <= top && lineY >= bottom) {
                if (matches.isNotEmpty()) drawMatches(x, lineY - lh, lh, linesBreak.items[i], linesBreak.items[i + 1], base.a)
                drawLine(font, shown, x, lineY, linesBreak.items[i], linesBreak.items[i + 1], base)
                val no = numbers?.getOrNull(i / 2) ?: 0
                val logical = logicalLineOfRow(numbers, i / 2)
                val mark = lineMarks[logical] ?: 0
                if (mark > 0) underline(shown, x, lineY - lh, linesBreak.items[i], linesBreak.items[i + 1], markColor(mark), base.a)
                if (no > 0) {
                    run.set(if (mark > 0) markColor(mark) else numberColor).a = base.a
                    font.color = run
                    font.draw(no.toString(), x - gutterWidth, lineY, gutterWidth - arc.scene.ui.layout.Scl.scl(6f), Align.right, false)
                }
            }
            offsetY -= lh
            i += 2
        }
        drawGhost(font, x, y, base)
        font.color = base
        font.data.markupEnabled = had
    }

    private val matchColor = Color.valueOf("d4a72c")

    private fun drawMatches(x: Float, bottom: Float, lh: Float, start: Int, end: Int, alpha: Float) {
        if (glyphPositions.size == 0) return
        // 第一个结束位置 ≥ 行首的匹配
        var lo = 0; var hi = matches.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (matches[mid].last < start) lo = mid + 1 else hi = mid }
        val last = glyphPositions.size - 1
        val origin = glyphPositions.get(start.coerceAtMost(last))
        var k = lo
        while (k < matches.size && matches[k].first < end) {
            val m = matches[k]
            val a = maxOf(m.first, start); val b = minOf(m.last + 1, end)
            if (b > a) {
                val x0 = glyphPositions.get(a.coerceAtMost(last)) - origin
                val x1 = glyphPositions.get(b.coerceAtMost(last)) - origin
                arc.graphics.g2d.Draw.color(matchColor, alpha * if (k == currentMatch) 0.55f else 0.22f)
                arc.graphics.g2d.Fill.crect(x + x0, bottom, x1 - x0, lh)
            }
            k++
        }
        arc.graphics.g2d.Draw.color()
    }

    private fun logicalLineOfRow(numbers: IntArray?, row: Int): Int {
        if (numbers == null) return 0
        var r = row.coerceAtMost(numbers.size - 1)
        while (r > 0 && numbers[r] == 0) r--
        return if (r >= 0) numbers[r] else 0
    }

    private fun markColor(mark: Int): Color = if (mark >= 2) errorColor else warnColor

    /** 行内容（去掉行首空白）下面画一条细线。 */
    private fun underline(text: CharSequence, x: Float, bottom: Float, start: Int, end: Int, c: Color, alpha: Float) {
        var s = start
        while (s < end && (text[s] == ' ' || text[s] == '\t')) s++
        if (s >= end || glyphPositions.size == 0) return
        val last = glyphPositions.size - 1
        val origin = glyphPositions.get(start.coerceAtMost(last))
        val x0 = glyphPositions.get(s.coerceAtMost(last)) - origin
        val x1 = glyphPositions.get(end.coerceAtMost(last)) - origin
        if (x1 <= x0) return
        arc.graphics.g2d.Draw.color(c, alpha * 0.9f)
        arc.graphics.g2d.Fill.crect(x + x0, bottom + arc.scene.ui.layout.Scl.scl(2f), x1 - x0, arc.scene.ui.layout.Scl.scl(1.5f))
        arc.graphics.g2d.Draw.color()
    }

    private val errorColor = Color.valueOf("e5534b")
    private val warnColor = Color.valueOf("d4a72c")

    private fun drawGhost(font: Font, x: Float, y: Float, base: Color) {
        val g = ghost?.takeIf { it.isNotEmpty() } ?: return
        if (arc.Core.scene?.keyboardFocus !== this || cursorLine * 2 >= linesBreak.size || glyphPositions.size == 0) return
        val origin = glyphPositions.get(linesBreak.items[cursorLine * 2].coerceAtMost(glyphPositions.size - 1))
        val dx = glyphPositions.get(cursor.coerceAtMost(glyphPositions.size - 1)) - origin
        run.set(numberColor).a = base.a * 0.9f
        font.color = run
        // 只画第一行（换行之后的部分原版也不预览）
        font.draw(g.substringBefore('\n'), x + dx, y - (cursorLine - firstLineShowing) * font.lineHeight, 0f, Align.left, false)
    }

    private fun drawLine(font: Font, text: CharSequence, x: Float, y: Float, start: Int, end: Int, base: Color) {
        if (end <= start || glyphPositions.size == 0) return
        val origin = glyphPositions.get(start.coerceAtMost(glyphPositions.size - 1))
        var s = start
        while (s < end) {
            val k = kinds[s]
            var e = s + 1
            while (e < end && kinds[e] == k) e++
            run.set(IniHighlighter.color(k)).a = base.a
            font.color = run
            val dx = glyphPositions.get(s.coerceAtMost(glyphPositions.size - 1)) - origin
            // GlyphLayout 会把每段第一个字形左移它的 xoffset（墨迹贴齐起点）；整行只该左移行首那个，
            // 中间各段补回来，否则等宽字体里冒号（xoffset 大）会叠到前一个字母上
            val shift = if (s == start) 0f else xoff(font, text[s]) - xoff(font, text[start])
            font.draw(text, x + dx + shift, y, s, e, 0f, Align.left, false)
            s = e
        }
    }

    private fun xoff(font: Font, ch: Char): Float = (font.data.getGlyph(ch)?.xoffset ?: 0) * font.data.scaleX

    /**
     * 当前裁剪区（外层 ScrollPane 绘制时压入的 scissor，屏幕坐标）换算到当前绘制坐标系后的上下边界；没有就不裁剪。
     * 绘制期间 Arc 会把祖先的 x/y 临时清零、ScrollPane 还会给 batch 加平移变换，所以不能用 localToStageCoordinates。
     */
    private fun viewport(): Pair<Float, Float> {
        val r = runCatching { ScissorStack.peek() }.getOrNull() ?: return Float.MAX_VALUE to -Float.MAX_VALUE
        val m = Draw.trans().`val`
        val scaleY = m[Mat.M11].takeIf { it != 0f } ?: 1f
        val ty = m[Mat.M12]
        return ((r.y + r.height - ty) / scaleY) to ((r.y - ty) / scaleY)
    }
}

/** 按字符给 INI 文本分类（纯函数，便于测试）。 */
object IniHighlighter {
    const val TEXT: Byte = 0
    const val SECTION: Byte = 1
    const val KEY: Byte = 2
    const val NUMBER: Byte = 3
    const val BOOL: Byte = 4
    const val COMMENT: Byte = 5
    const val DIRECTIVE: Byte = 6
    const val BLOCK: Byte = 7
    const val PUNCT: Byte = 8

    // 按种类：正文、节、键、数字、布尔、注释、指令、多行块、标点；亮色 / 暗色两套
    // 照 IntelliJ IDEA：暗色 Darcula（节 / 布尔 橙、键 紫、数字 蓝、多行块 绿、指令 黄），亮色 IntelliJ Light
    private val light = arrayOf("080808", "0033b3", "871094", "1750eb", "0033b3", "8c8c8c", "9e880d", "067d17", "080808").map(Color::valueOf)
    private val dark = arrayOf("a9b7c6", "cc7832", "9876aa", "6897bb", "cc7832", "808080", "bbb529", "6a8759", "a9b7c6").map(Color::valueOf)

    fun color(kind: Byte): Color = (if (RwTheme.dark) dark else light)[kind.toInt()]

    /** 只分类一行（[out] 至少行长）；[inBlock] 是行首是否在三引号块里，返回行尾是否还在块里。 */
    fun classifyLine(line: CharSequence, inBlock: Boolean, out: ByteArray): Boolean {
        java.util.Arrays.fill(out, 0, line.length, TEXT)
        return line(line, 0, line.length, out, inBlock)
    }

    fun classify(text: CharSequence): ByteArray {
        val out = ByteArray(text.length)
        var inBlock = false
        var lineStart = 0
        while (lineStart <= text.length) {
            var lineEnd = lineStart
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            inBlock = line(text, lineStart, lineEnd, out, inBlock)
            lineStart = lineEnd + 1
        }
        return out
    }

    private fun line(t: CharSequence, start: Int, end: Int, out: ByteArray, block: Boolean): Boolean {
        var inBlock = block
        if (inBlock) {
            val close = indexOf(t, "\"\"\"", start, end)
            val stop = if (close < 0) end else close + 3
            out.fill(BLOCK, start, stop)
            if (close < 0) return true
            return line(t, stop, end, out, false)
        }
        var i = start
        while (i < end && (t[i] == ' ' || t[i] == '\t')) i++
        if (i >= end) return false
        when {
            t[i] == '#' -> { out.fill(COMMENT, i, end); return false }
            t[i] == '[' -> {
                val close = indexOf(t, "]", i, end)
                out.fill(SECTION, i, if (close < 0) end else close + 1)
                return false
            }
            t[i] == '@' -> {
                var e = i
                while (e < end && !t[e].isWhitespace()) e++
                out.fill(DIRECTIVE, i, e)
                i = e
            }
        }
        val sep = (i until end).firstOrNull { t[it] == ':' || t[it] == '=' }
        var valueStart = i
        if (sep != null) {
            out.fill(KEY, i, sep)
            out[sep] = PUNCT
            valueStart = sep + 1
        }
        val open = indexOf(t, "\"\"\"", valueStart, end)
        val valueEnd = if (open < 0) end else open
        value(t, valueStart, valueEnd, out)
        if (open >= 0) {
            val close = indexOf(t, "\"\"\"", open + 3, end)
            if (close < 0) { out.fill(BLOCK, open, end); inBlock = true }
            else { out.fill(BLOCK, open, close + 3); return line(t, close + 3, end, out, false) }
        }
        return inBlock
    }

    private fun value(t: CharSequence, start: Int, end: Int, out: ByteArray) {
        var i = start
        while (i < end) {
            val c = t[i]
            when {
                c.isDigit() || (c == '-' && i + 1 < end && t[i + 1].isDigit() && (i == start || !t[i - 1].isLetterOrDigit())) -> {
                    var e = i + 1
                    while (e < end && (t[e].isDigit() || t[e] == '.')) e++
                    val wordBefore = i > start && (t[i - 1].isLetter() || t[i - 1] == '_')
                    if (!wordBefore && (e >= end || !t[e].isLetter())) out.fill(NUMBER, i, e)
                    i = e
                }
                c.isLetter() -> {
                    var e = i + 1
                    while (e < end && (t[e].isLetterOrDigit() || t[e] == '_')) e++
                    val w = t.subSequence(i, e).toString()
                    if (w.equals("true", true) || w.equals("false", true)) out.fill(BOOL, i, e)
                    i = e
                }
                else -> i++
            }
        }
    }

    private fun indexOf(t: CharSequence, s: String, from: Int, to: Int): Int {
        var i = from
        while (i + s.length <= to) {
            if ((s.indices).all { t[i + it] == s[it] }) return i
            i++
        }
        return -1
    }
}
