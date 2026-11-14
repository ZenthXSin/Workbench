package io.github.zenthxsin.rustwright.ui.widgets.code

import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.graphics.g2d.Lines
import arc.graphics.g2d.Font
import arc.input.ImeClient
import arc.input.KeyCode
import arc.math.geom.Vec2
import arc.scene.Element
import arc.scene.event.FocusListener
import arc.scene.event.InputEvent
import arc.scene.event.InputListener
import arc.scene.event.Touchable
import arc.scene.ui.Label
import arc.scene.ui.TextField
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.scene.ui.layout.WidgetGroup
import arc.util.Align
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 代码编辑器（替代基于 TextArea 的编辑框，参考 Sora 编辑器的手感）：
 * - 文档按行存（[CodeDocument]），着色按行增量（[LineHighlights]），只画可见行：打字的开销和文件大小无关
 * - 左侧固定行号栏，当前行底色；不自动换行，横竖都能拖动、惯性滚动
 * - 点按放光标，双击 / 长按选词，选区两端有拖柄，选中后浮出「全选 复制 剪切 粘贴」
 * - 输入法只拿光标附近几十行（[ImeClient] 窗口），回写时按差异改文档；光标走到窗口边上再重新同步
 * - 实体键盘：方向键（带 Shift 选择）、Home/End、翻页、退格 / 删除、回车自动缩进、Ctrl+A/C/X/V/Z/Y/S
 * 补全弹窗、问题标记、查找高亮、幽灵字由宿主通过 [ghost]、[lineMarks]、[matches] 等属性提供。
 */
class CodeEditor(val doc: io.github.zenthxsin.rustwright.core.editor.api.TextDocument, highlighter: LineHighlighter?, private val palette: (Byte) -> Color) : WidgetGroup(), ImeClient {
    /** 每种着色器一份缓存（各自挂一次文档监听，切换时不再新挂，免得越积越多）。 */
    private val highlightCache = HashMap<LineHighlighter, LineHighlights>()
    private var highlights = highlighter?.let { h -> LineHighlights(doc, h).also { highlightCache[h] = it } }

    /** 换语法着色（INI 用 INI 的，txt / md 等不着色）。 */
    fun setHighlighter(h: LineHighlighter?) {
        val next = h?.let { highlightCache.getOrPut(it) { LineHighlights(doc, it) } }
        if (next !== highlights) next?.reset()
        highlights = next
    }

    // ---- 外观 ----
    var font: Font = fontAtZoom(zoom)
        // 小字号复用预览图集；每次访问恢复本编辑器的比例，避免多个编辑器串改共享字体。
        get() = when {
            previewSize > 0f -> scaledZoomFont(previewSize)
            fontSize < RASTER_MIN_ZOOM -> scaledZoomFont(fontSize)
            else -> field
        }
        set(v) { field = v; widths.clear(); maxWidthSeen = 0f }

    /** 字号（sp）：最小为默认大小的千分之一；8sp 以下连续缩放并复用图集，其余按 0.5sp 一档。 */
    var fontSize: Float = zoom
        set(v) {
            val z = normalizeZoom(v)
            if (z == field) return
            field = z; zoom = z
            font = fontAtZoom(z)
            clampScroll()
        }

    // 配色照 IntelliJ IDEA（暗色 Darcula / 亮色 IntelliJ Light）
    private val dk = RwTheme.dark
    private fun idea(dark: String, light: String) = Color.valueOf(if (dk) dark else light)
    val background = idea("2b2b2b", "ffffff")
    val gutterBackground = idea("313335", "f7f8fa")
    val lineNumberColor = idea("606366", "aeb3c2")
    val currentLineNumberColor = idea("a4a3a3", "767a8a")
    val dividerColor = idea("3c3f41", "ebecf0")
    val currentLineColor = idea("323232", "fcfaed")
    val selectionColor = idea("214283", "a6d2ff")
    val cursorColor = idea("bbbbbb", "000000")
    val handleColor = idea("4a88c7", "3574f0")
    val ghostColor = idea("787878", "a0a0a0")
    val matchColor = idea("32593d", "ffe48a")
    /** 光标所在标识符的其他出现处（IDEA 的 Identifier under caret）。 */
    val usageColor = idea("344134", "edebfc")
    val indentGuideColor = idea("393939", "ebecf0")
    val errorColor = idea("ff6b68", "f50000")
    val warnColor = idea("be9117", "be9117")
    /** 警告整段底色（IDEA 的 Warning：暗色暗黄底，亮色浅黄底）。 */
    val warnBackground = idea("52503a", "f6ebbc")
    var editable = true

    // ---- 宿主提供的叠加信息 ----
    /** 光标后的灰字（补全预览）。 */
    var ghost: String? = null
    /** 0 基行号 → 2 错误 / 1 警告（没有 [marks] 时整行标）。 */
    var lineMarks: Map<Int, Int> = emptyMap()
    /** 精确到列的问题：错误画红色波浪线，警告铺黄底；右边缘同时画一道标记条（IDEA 的 error stripe）。 */
    var marks: List<Mark> = emptyList()
    /** 查找结果（按位置排序）和当前项。 */
    var matches: List<Pair<Pos, Pos>> = emptyList()
    var currentMatch = -1
    /** Tab 键 / 回车前先问宿主（补全开着时接受补全），返回 true 表示已处理。 */
    var onTab: (() -> Boolean)? = null
    var onEnter: (() -> Boolean)? = null
    var onSave: (() -> Unit)? = null
    /** 文件末尾额外留出的空行数（不画行号）：最后几行也能滚到手指好点的位置。0 = 旧规则（留下半屏）。 */
    var tailRows = 0
    /** 点按落在某个位置时先问一下外面：返回 true 表示外面处理了（如点布尔值直接切换），不移动光标、不弹键盘。 */
    var onTapAt: ((Pos) -> Boolean)? = null

    // ---- 光标 / 选区 ----
    var cursor = Pos(0, 0); private set
    /** 选区另一端；null 或等于光标时没有选区。 */
    var anchor: Pos? = null; private set
    private var composing: Pair<Pos, Pos>? = null
    /** 上下移动时记住的横坐标。 */
    private var preferredX = -1f
    private var blink = 0f

    // ---- 滚动 ----
    var scrollX = 0f; private set
    var scrollY = 0f; private set
    private var vx = 0f
    private var vy = 0f
    private var maxWidthSeen = 0f

    // ---- 触摸 ----
    private var pressed = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveAt = 0L
    private var longPressed = false
    private var lastTapAt = 0L
    private var lastTapPos: Pos? = null
    /** 拖的是哪个拖柄：1 选区起点、2 终点、0 没拖。 */
    private var dragHandle = 0
    private var touchMode = false
    private var axisLock = 0

    // ---- 输入法窗口 ----
    private var imeStartLine = 0
    private var imeEndLine = 0
    private var imeTextCache = ""
    private var syncPending = false

    /** 每个字符串的累计横坐标缓存（同内容的行共用）。 */
    private val widths = HashMap<String, FloatArray>()
    private val menu = Table()
    private val tmp = Vec2()

    val hasSelection: Boolean get() = anchor != null && anchor != cursor
    val selectionStart: Pos get() = if (hasSelection) minOf(anchor!!, cursor) else cursor
    val selectionEnd: Pos get() = if (hasSelection) maxOf(anchor!!, cursor) else cursor
    val selectedText: String get() = if (hasSelection) doc.textIn(selectionStart, selectionEnd) else ""

    private val lh get() = font.lineHeight
    private val basePad get() = Scl.scl(6f)
    /** 顶部被上方浮层盖住的高度：行从这条线下面开始排，不改控件本身的大小（浮层每帧变高也不用重排）。 */
    private var topInset = 0f
    private val padTop get() = basePad + topInset

    /**
     * 改顶部让位：先把滚动补上同样的量，屏幕上的代码原地不动；只有光标行会被浮层盖住（或挤出底部）时，才逐帧刚好滚到看得见的位置。
     */
    fun setTopInset(v: Float) {
        val d = v - topInset
        if (d == 0f) return
        topInset = v
        scrollY += d
        clampScroll()
        ensureCursorVisible()
    }
    private val padLeft get() = Scl.scl(6f)
    /** 行号和正文同一个字号（跟着缩放），行号栏宽度 = 最长行号 + 两边各 [GUTTER_PAD]。 */
    // 每帧会被问很多次（文字起点、可视宽度、滚动范围都依赖它）：数位用除法数，不转字符串
    private val gutterWidth: Float get() {
        var n = doc.lineCount; var digits = 1
        while (n >= 10) { n /= 10; digits++ }
        return digits * advance('0') + Scl.scl(GUTTER_PAD * 2)
    }
    private val textLeft get() = gutterWidth + padLeft
    private val focused get() = scene?.keyboardFocus === this

    init {
        touchable = Touchable.enabled
        buildMenu()
        doc.addListener { e ->
            if (e.origin != Edit.Origin.IME && focused) syncPending = true
            blink = 0f
        }
        addListener(object : FocusListener() {
            override fun keyboardFocusChanged(event: FocusListener.FocusEvent, element: Element, focused: Boolean) {
                if (!focused && element === this@CodeEditor) {
                    composing = null
                    TextField.inputBridge?.end(this@CodeEditor)
                }
            }
        })
        addListener(Input())
    }

    // ================= 对外操作 =================

    /** 换整篇文字（打开文件）。 */
    fun setText(text: String) {
        doc.setText(text)
        highlightCache.values.forEach { it.reset() }
        cursor = Pos(0, 0); anchor = null; composing = null
        scrollX = 0f; scrollY = 0f; maxWidthSeen = 0f
        widths.clear()
        if (focused) syncPending = true
    }

    fun setCursor(p: Pos, keepAnchor: Boolean = false) {
        val c = doc.clamp(p)
        if (keepAnchor) { if (anchor == null) anchor = cursor } else anchor = null
        cursor = c
        preferredX = -1f
        blink = 0f
        hideMenuIfEmpty()
        if (focused) syncPending = true
    }

    /** 选中 [a]..[b]；[handles] 为 true 时显示拖柄（触摸选词），程序选中（查找）不显示。 */
    fun select(a: Pos, b: Pos, handles: Boolean = false) {
        anchor = doc.clamp(a); cursor = doc.clamp(b)
        touchMode = handles
        preferredX = -1f
        if (focused) syncPending = true
    }

    fun selectAll() { select(Pos(0, 0), doc.end(), handles = touchMode); showMenu() }

    /** 用 [text] 替换选区（没有选区就在光标处插入），光标放到插入段末尾再左移 [cursorBack] 个字符。 */
    fun insert(text: String, cursorBack: Int = 0, origin: Edit.Origin = Edit.Origin.PROGRAM) {
        if (!editable) return
        val end = doc.replace(selectionStart, selectionEnd, text, origin, cursor)
        anchor = null
        cursor = doc.clamp(Pos(end.line, end.col - cursorBack))
        preferredX = -1f
        ensureCursorVisible()
    }

    /** 替换任意区间（补全、查找替换用），不动选区以外的东西。 */
    /**
     * 程序改字（字段控件、注释、格式化……）：光标和选区锚点跟着这次编辑平移，
     * 不能停在原来的列上——行变短后光标越界，下一帧画「同名高亮」就会崩。
     */
    fun replace(a: Pos, b: Pos, text: String): Pos {
        val keepCursor = cursor; val keepAnchor = anchor
        val end = doc.replace(a, b, text, Edit.Origin.PROGRAM, cursor)
        val s0 = doc.clamp(minOf(a, b)); val e0 = maxOf(a, b)
        fun map(p: Pos): Pos {
            val m = when {
                p <= s0 -> p
                p >= e0 -> if (p.line == e0.line) Pos(end.line, end.col + (p.col - e0.col)) else Pos(p.line + (end.line - e0.line), p.col)
                else -> end
            }
            return doc.clamp(m)
        }
        cursor = map(keepCursor)
        anchor = keepAnchor?.let { map(it) }?.takeIf { it != cursor }
        return end
    }

    fun undo() { doc.undo()?.let { anchor = null; cursor = it; ensureCursorVisible() } }
    fun redo() { doc.redo()?.let { anchor = null; cursor = it; ensureCursorVisible() } }

    /** 取得焦点并弹出键盘。 */
    fun focusAndShowKeyboard() {
        scene?.setKeyboardFocus(this)
        TextField.inputBridge?.begin(this)
    }

    /** 光标左下角（光标所在行的底边）在本控件里的坐标。 */
    fun cursorLocal(out: Vec2): Vec2 = out.set(textLeft - scrollX + xOf(cursor), height - padTop + scrollY - (rowOfPos(cursor) + 1) * lh)

    fun lineHeight(): Float = lh
    /** 编辑器当前字号下一个等宽字符的宽度（补全弹窗按它把候选和正在输入的词对齐）。 */
    fun charWidth(): Float = advance('0')

    // ================= 坐标换算 =================

    private fun advance(c: Char): Float {
        if (c == '\t') return advance(' ') * 4
        val g = font.data.getGlyph(c) ?: font.data.getGlyph(' ') ?: return font.lineHeight * 0.5f
        return g.xadvance * font.data.scaleX
    }

    private fun xs(line: String): FloatArray {
        widths[line]?.let { return it }
        if (widths.size > 4000) widths.clear()
        val a = FloatArray(line.length + 1)
        for (i in line.indices) a[i + 1] = a[i] + advance(line[i])
        widths[line] = a
        if (a[line.length] > maxWidthSeen) maxWidthSeen = a[line.length]
        return a
    }

    // ---- 自动换行：一行文档拆成几段显示（视觉行 = row） ----

    /** 自动换行：开着时没有横向滚动，长行折成几段，续行缩进两格。 */
    var wrap: Boolean = wrapDefault
        set(v) { if (field != v) { field = v; wrapDefault = v; layoutValid = false; scrollX = 0f; clampScroll(); ensureCursorVisible() } }

    // 排版缓存键：每帧要查很多次（行号、错误条、滚动范围都会问总行数），只比数字，不拼字符串
    private var layoutValid = false
    private var layoutVersion = -1
    private var layoutLines = -1
    private var layoutFont = 0
    private var layoutScale = 0f
    private var layoutAvail = -1f
    private var layoutWrap = false
    private var rowStart = IntArray(1)
    private var segStarts: Array<IntArray> = emptyArray()
    private val breakCache = HashMap<String, IntArray>()
    private val ZERO = intArrayOf(0)
    private val contIndent get() = advance(' ') * 2

    /** 换行布局：文档、字号、宽度变了才重算；每行的折点按行内容缓存。 */
    private fun wrapLayout() {
        val avail = viewWidth
        val f = font
        val fontId = System.identityHashCode(f)
        val scale = f.data.scaleX
        if (layoutValid && layoutVersion == doc.version && layoutLines == doc.lineCount && layoutFont == fontId &&
            layoutScale == scale && layoutAvail == avail && layoutWrap == wrap) return
        if (layoutFont != fontId || layoutScale != scale || layoutAvail != avail) breakCache.clear()
        layoutValid = true; layoutVersion = doc.version; layoutLines = doc.lineCount
        layoutFont = fontId; layoutScale = scale; layoutAvail = avail; layoutWrap = wrap
        val n = doc.lineCount
        if (!wrap || avail <= advance('0') * 4) {
            rowStart = IntArray(n + 1) { it }
            segStarts = emptyArray()
            return
        }
        val starts = Array(n) { ZERO }
        val rs = IntArray(n + 1)
        for (l in 0 until n) {
            val line = doc.line(l)
            val st = if (line.isEmpty()) ZERO else breakCache.getOrPut(line) { breaks(line, avail) }
            starts[l] = st
            rs[l + 1] = rs[l] + st.size
        }
        rowStart = rs
        segStarts = starts
    }

    private fun breaks(line: String, avail: Float): IntArray {
        val a = xs(line)
        if (a[line.length] <= avail) return ZERO
        val out = ArrayList<Int>()
        out += 0
        var s = 0
        val ind = contIndent
        while (true) {
            val room = avail - (if (s > 0) ind else 0f)
            if (a[line.length] - a[s] <= room) break
            var e = s + 1
            while (e < line.length && a[e + 1] - a[s] <= room) e++
            // 尽量在空格后折（只往回找后半段，免得一行太短）
            var sp = e
            while (sp > s + (e - s) / 2 && line[sp - 1] != ' ') sp--
            if (sp > s + (e - s) / 2) e = sp
            if (e >= line.length) break
            out += e
            s = e
        }
        return out.toIntArray()
    }

    // 问题标记可能还停在改动前的行号上（检查结果是异步回来的），越界的一律当一段
    private fun segsOf(l: Int): IntArray { wrapLayout(); return if (segStarts.isEmpty() || l !in segStarts.indices) ZERO else segStarts[l] }
    private val totalRows: Int get() { wrapLayout(); return rowStart[doc.lineCount] }
    private fun rowOf(l: Int): Int { wrapLayout(); return rowStart[l.coerceIn(0, doc.lineCount)] }
    private fun rowsIn(l: Int): Int = segsOf(l).size

    /** 视觉行 → 文档行（二分）。 */
    private fun lineAtRow(r: Int): Int {
        wrapLayout()
        var lo = 0; var hi = doc.lineCount - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (rowStart[mid] <= r) lo = mid else hi = mid - 1 }
        return lo
    }

    private fun segIndex(l: Int, col: Int): Int {
        val st = segsOf(l)
        var i = st.size - 1
        while (i > 0 && st[i] > col) i--
        return i
    }

    /** 第 [seg] 段的横向偏移：段首字的 x 减去续行缩进。 */
    private fun segShift(a: FloatArray, st: IntArray, seg: Int): Float = a[st[seg]] - (if (seg > 0) contIndent else 0f)

    /** 位置所在的视觉行。 */
    private fun rowOfPos(p: Pos): Int = rowOf(p.line) + segIndex(p.line, p.col)

    /** 位置在它那一视觉行里的横坐标。 */
    private fun xOf(p: Pos): Float {
        val l = p.line.coerceIn(0, doc.lineCount - 1)
        val line = doc.line(l)
        val a = xs(line)
        val col = p.col.coerceIn(0, line.length)
        val st = segsOf(l)
        return a[col] - segShift(a, st, segIndex(l, col))
    }

    /** 把 [l] 行 [c0]..[c1] 列按视觉行拆开，回调（视觉行, 行内起点 x, 行内终点 x）。 */
    private inline fun segments(l: Int, c0: Int, c1: Int, extendEnd: Boolean = false, f: (Int, Float, Float) -> Unit) {
        val line = doc.line(l)
        val len = line.length
        val a = xs(line)
        val st = segsOf(l)
        val r0 = rowOf(l)
        // [extendEnd] 且 c1 越过行尾（选区跨到下一行）：最后一段多画一个空格宽，看得出换行也选中了
        val pastEnd = extendEnd && c1 > len
        for (i in st.indices) {
            val isLast = i == st.size - 1
            val ss = st[i]; val se = if (isLast) len else st[i + 1]
            val lo = max(c0, ss); val hi = min(c1, se)
            if (lo > hi || (lo == hi && !(isLast && pastEnd))) continue
            val sh = segShift(a, st, i)
            var x1 = a[hi] - sh
            if (isLast && pastEnd) x1 += advance(' ')
            f(r0 + i, a[lo] - sh, x1)
        }
    }

    /** 本控件坐标 → 文档位置（取最近的字符边界）。 */
    fun posAt(lx: Float, ly: Float): Pos {
        val row = floor((height - padTop - ly + scrollY) / lh).toInt().coerceIn(0, max(0, totalRows - 1))
        val line = lineAtRow(row)
        val seg = row - rowOf(line)
        val text = doc.line(line)
        val a = xs(text)
        val st = segsOf(line)
        val ss = st[seg]; val se = if (seg + 1 < st.size) st[seg + 1] - 1 else text.length
        val x = lx - textLeft + scrollX + segShift(a, st, seg)
        var lo = ss; var hi = se.coerceAtLeast(ss)
        while (lo < hi) { val mid = (lo + hi) ushr 1; if ((a[mid] + a[mid + 1]) / 2f < x) lo = mid + 1 else hi = mid }
        return Pos(line, lo)
    }

    private val viewWidth get() = max(0f, width - textLeft - Scl.scl(8f))
    /** 内容超过一屏时允许把最后一行滚到屏幕中间（和 Sora 一样留出下半屏）。 */
    private val maxScrollY: Float get() {
        if (tailRows > 0) return max(0f, (totalRows + tailRows) * lh + padTop + basePad - height)
        val content = totalRows * lh + padTop + basePad
        return if (content <= height) 0f else content - height * 0.5f
    }
    private val maxScrollX get() = if (wrap) 0f else max(0f, maxWidthSeen - viewWidth + Scl.scl(24f))

    private fun clampScroll() {
        scrollY = scrollY.coerceIn(0f, maxScrollY)
        scrollX = scrollX.coerceIn(0f, maxScrollX)
    }

    fun ensureCursorVisible() {
        if (height <= 0f) return
        val top = rowOfPos(cursor) * lh
        val view = height - padTop - basePad
        val bottomMargin = lh * 1.5f
        if (top < scrollY) scrollY = top
        else if (top + lh + bottomMargin > scrollY + view) scrollY = top + lh + bottomMargin - view
        val cx = xOf(cursor)
        val margin = Scl.scl(24f)
        if (cx < scrollX + margin) scrollX = max(0f, cx - margin)
        else if (cx > scrollX + viewWidth - margin) scrollX = cx - viewWidth + margin
        if (!wrap) maxWidthSeen = max(maxWidthSeen, cx)
        clampScroll()
    }

    /** 竖直方向滚动一段距离（上方面板开合时用它抵消，让代码在屏幕上的位置不动）。 */
    fun scrollBy(dy: Float) { scrollY += dy; clampScroll() }

    /** 把光标所在行滚到可视区中间偏上（跳转、查找时用）。 */
    fun revealCursor() {
        scrollY = (rowOfPos(cursor) * lh - height * 0.35f)
        clampScroll()
        ensureCursorVisible()
    }

    /** 大小变了要不要自己把光标滚进来。外面要先补偿滚动位置时（上方面板开合）先关掉，补偿完调 [settleAfterResize]。 */
    var revealOnResize = true

    override fun sizeChanged() {
        super.sizeChanged()
        if (revealOnResize) settleAfterResize()
    }

    fun settleAfterResize() { if (focused) ensureCursorVisible() else clampScroll() }

    // ================= 每帧 =================

    override fun act(delta: Float) {
        super.act(delta)
        blink += delta
        // 长按：选词
        if (pressed && !moved && !longPressed && dragHandle == 0 && System.currentTimeMillis() - downAt > 450) {
            longPressed = true
            selectWordAt(posAt(downX, downY))
        }
        // 惯性滚动
        if (!pressed && (abs(vx) > 1f || abs(vy) > 1f)) {
            scrollX += vx * delta; scrollY += vy * delta
            val f = exp(-2.25f * delta)
            vx *= f; vy *= f
            clampScroll()
            if (scrollY <= 0f || scrollY >= maxScrollY) vy = 0f
            if (scrollX <= 0f || scrollX >= maxScrollX) vx = 0f
        }
        if (syncPending) {
            syncPending = false
            if (focused) TextField.inputBridge?.sync(this)
        }
        layoutMenu()
    }

    // ================= 绘制 =================

    override fun draw() {
        validate()
        val alpha = color.a * parentAlpha
        Draw.color(background, background.a * alpha)
        Fill.crect(x, y, width, height)
        if (clipBegin()) {
            drawContent(alpha)
            clipEnd()
        }
        Draw.reset()
        super.draw()
    }

    /** 缩略图画法：按像素行抽样，每行一条色条（起止按缩进和行长估算，颜色取这一行第一个字的语法色，有错误的行标红）。 */
    private fun drawOverview(first: Int, last: Int, top: Float, left: Float, alpha: Float) {
        val lh = lh
        val step = max(1, ceil(Scl.scl(1f) / lh).toInt())
        val charW = max(advance('0'), Scl.scl(0.02f))
        val barH = max(lh * step, Scl.scl(1f))
        val bad = HashSet<Int>()
        for (m in marks) if (m.level >= 2) bad += m.line
        var l = first
        while (l <= last) {
            val line = doc.line(l)
            val lead = line.indexOfFirst { it != ' ' && it != '\t' }
            if (lead >= 0) {
                val end = line.trimEnd().length
                val c = if (l in bad || (lineMarks[l] ?: 0) >= 2) errorColor else palette(highlights?.kindsOf(l)?.getOrNull(lead) ?: 0)
                Draw.color(c, c.a * alpha * 0.75f)
                Fill.crect(left + lead * charW, top - (rowOf(l) + 1) * lh, max((end - lead) * charW, Scl.scl(1f)), barH)
            }
            l += step
        }
        Draw.reset()
    }

    private fun drawContent(alpha: Float) {
        val f = font
        val lh = lh
        wrapLayout()
        val firstRow = max(0, floor(scrollY / lh).toInt())
        val lastRow = min(max(0, totalRows - 1), firstRow + (height / lh).toInt() + 1)
        val first = lineAtRow(firstRow)
        val last = lineAtRow(lastRow)
        val top = y + height - padTop + scrollY
        val left = x + textLeft - scrollX
        val showCursorLine = !hasSelection
        val curRow0 = rowOf(cursor.line)
        val curRows = rowsIn(cursor.line)

        // 当前行底色（整行，包括折成的几段和行号栏）
        if (showCursorLine && cursor.line in first..last) {
            Draw.color(currentLineColor, currentLineColor.a * alpha)
            Fill.crect(x, top - (curRow0 + curRows) * lh, width, lh * curRows)
        }
        // 缩到行高不足 1.5 像素时（最小可到默认的千分之一），整篇都在可见范围里：逐字画每帧要画上万个字形，
        // 改成缩略图画法，每个像素行只画一条代表这一行代码的色条
        if (lh < Scl.scl(OVERVIEW_LINE_HEIGHT)) {
            drawOverview(first, last, top, left, alpha)
            return
        }
        // 缩进参考线、光标处标识符的其他出现、警告底色
        drawIndentGuides(first, last, top, left, alpha)
        if (!hasSelection) drawUsages(first, last, top, left, alpha)
        for (m in marks) if (m.level < 2 && m.line in first..last) markRect(m, top, left, warnBackground, alpha)
        // 查找结果、选区
        if (matches.isNotEmpty()) drawMatches(first, last, top, left, alpha)
        if (hasSelection) drawRange(selectionStart, selectionEnd, first, last, top, left, selectionColor, alpha)

        // 文字
        val had = f.data.markupEnabled
        f.data.markupEnabled = false
        val run = Color()
        val drop = textDrop
        for (l in first..last) {
            val line = doc.line(l)
            if (line.isEmpty()) continue
            val a = xs(line)
            val st = segsOf(l)
            val r0 = rowOf(l)
            val kinds = highlights?.kindsOf(l)
            // 有错误的字整段改成红色（IDEA 的未解析 / 错误文字）
            val errs = marks.filter { it.line == l && it.level >= 2 }
            fun bad(i: Int) = errs.any { i >= it.start && i < it.end }
            for (seg in st.indices) {
                val row = r0 + seg
                if (row < firstRow || row > lastRow) continue
                val rowTop = top - row * lh - drop
                val ss = st[seg]; val se = if (seg + 1 < st.size) st[seg + 1] else line.length
                val base = left - segShift(a, st, seg)
                // 横向只画可见的字
                val minX = scrollX - Scl.scl(16f) + a[ss]; val maxX = scrollX + width + a[ss]
                var s = if (wrap) ss else lowerBound(a, minX).coerceIn(ss, se)
                val end = if (wrap) se else lowerBound(a, maxX).coerceIn(ss, se)
                while (s < end) {
                    if (line[s] == '\t' || line[s] == ' ') { s++; continue }
                    val k = kinds?.get(s) ?: 0
                    val b = errs.isNotEmpty() && bad(s)
                    var e = s + 1
                    while (e < end && (kinds?.get(e) ?: 0) == k && line[e] != '\t' && (errs.isEmpty() || bad(e) == b)) e++
                    run.set(if (b) errorColor else palette(k)).a *= alpha
                    f.color = run
                    // GlyphLayout 会把每段第一个字形左移它的 xoffset，这里补回来，段和段之间才对齐
                    f.draw(line, base + a[s] + xoff(line[s]), rowTop, s, e, 0f, Align.left, false)
                    s = e
                }
                // 续行开头一个小折角，看得出是上一行接下来的
                if (seg > 0) {
                    Draw.color(lineNumberColor, alpha * 0.7f)
                    val cx = left + contIndent * 0.35f; val cy = top - row * lh - lh * 0.5f
                    Lines.stroke(Scl.scl(1.2f))
                    Lines.line(cx, cy + lh * 0.22f, cx, cy)
                    Lines.line(cx, cy, cx + contIndent * 0.45f, cy)
                }
            }
            if (marks.isEmpty()) lineMarks[l]?.let { mark -> if (mark >= 2) {
                val lead = line.indexOfFirst { it != ' ' && it != '\t' }
                if (lead >= 0) segments(l, lead, line.length) { row, x0, x1 ->
                    Draw.color(errorColor, 0.9f * alpha); Fill.crect(left + x0, top - (row + 1) * lh + Scl.scl(1.5f), x1 - x0, Scl.scl(1.5f))
                }
            } }
        }
        for (m in marks) if (m.level >= 2 && m.line in first..last) {
            val len = doc.line(m.line).length
            val s0 = m.start.coerceIn(0, len); val e0 = m.end.coerceIn(s0, len)
            if (e0 > s0) segments(m.line, s0, e0) { row, x0, x1 -> wave(left + x0, top - (row + 1) * lh + Scl.scl(2f), x1 - x0, errorColor, alpha) }
            else { val px = xOf(Pos(m.line, s0)); wave(left + px, top - (rowOfPos(Pos(m.line, s0)) + 1) * lh + Scl.scl(2f), advance('0'), errorColor, alpha) }
        }
        // 输入法组合区下划线
        composing?.let { (cs, ce) -> if (cs.line == ce.line && cs.line in first..last) {
            Draw.color(cursorColor, alpha * 0.8f)
            segments(cs.line, cs.col, ce.col) { row, x0, x1 -> Fill.crect(left + x0, top - (row + 1) * lh + Scl.scl(1f), x1 - x0, Scl.scl(1.5f)) }
        } }
        val curRow = rowOfPos(cursor)
        // 幽灵字
        val g = ghost
        if (!g.isNullOrEmpty() && focused && !hasSelection && cursor.line in first..last) {
            run.set(ghostColor).a *= alpha * 0.85f
            f.color = run
            val text = g.substringBefore('\n')
            f.draw(text, left + xOf(cursor) + xoff(text[0]), top - curRow * lh - drop, 0, text.length, 0f, Align.left, false)
        }
        f.color = Color.white
        f.data.markupEnabled = had

        // 光标
        if (focused && editable && (blink % 1f) < 0.55f && cursor.line in first..last) {
            Draw.color(cursorColor, alpha)
            val inset = min(Scl.scl(2f), lh * 0.1f)
            Fill.crect(left + xOf(cursor) - Scl.scl(0.75f), top - (curRow + 1) * lh + inset, Scl.scl(1.6f), lh - inset * 2f)
        }

        // 行号栏（盖在横向滚动的文字上面）：行号和正文同字号，右对齐（按数字宽度手动对齐，不用 GlyphLayout 的右对齐，1 和 0 宽窄不一会歪）
        val gw = gutterWidth
        Draw.color(gutterBackground, gutterBackground.a * alpha)
        Fill.crect(x, y, gw, height)
        if (showCursorLine && cursor.line in first..last) {
            Draw.color(currentLineColor, currentLineColor.a * alpha)
            Fill.crect(x, top - (curRow0 + curRows) * lh, gw, lh * curRows)
        }
        Draw.color(dividerColor, dividerColor.a * alpha)
        Fill.crect(x + gw - Scl.scl(1f), y, Scl.scl(1f), height)
        f.data.markupEnabled = false
        val digit = advance('0')
        for (l in first..last) {
            val row = rowOf(l)
            if (row < firstRow - 1 || row > lastRow) continue
            run.set(if (l == cursor.line) currentLineNumberColor else lineNumberColor).a *= alpha
            f.color = run
            val label = (l + 1).toString()
            val lx = x + gw - Scl.scl(GUTTER_PAD) - label.length * digit
            f.draw(label, lx + xoff(label[0]), top - row * lh - drop, 0, label.length, 0f, Align.left, false)
        }
        f.color = Color.white
        f.data.markupEnabled = had

        // 拖柄盖在行号栏上面（选区从行首开始时左拖柄伸进行号栏）
        if (hasSelection && touchMode) drawHandles(top, left, alpha)
        // 右边缘问题条（IDEA 的 error stripe）：一屏放得下时和左边的行对齐，放不下时按整篇比例缩放到这一条上
        if (marks.isNotEmpty() || lineMarks.isNotEmpty()) {
            // 滚动范围对所有标记都一样，先算一次（错误多时逐个算会把绘制拖慢）
            val maxY = maxScrollY
            for ((l, lv) in markLevels()) {
                if (l !in 0 until doc.lineCount) continue
                val c = if (lv >= 2) errorColor else warnColor
                Draw.color(c, alpha * 0.85f)
                val (top0, h0) = stripeOf(l, maxY)
                Fill.crect(x + width - Scl.scl(5f), y + top0 - h0, Scl.scl(4f), h0)
            }
        }
        // 滚动条（拖动 / 惯性时显示；按住右边缘拖动时变宽，可以快速翻）
        if ((pressed && moved) || abs(vy) > 1f || thumbDrag) {
            if (maxScrollY > 0f) {
                val (ty, h) = thumbRect()
                val w = if (thumbDrag) Scl.scl(6f) else Scl.scl(3f)
                Draw.color(if (thumbDrag) handleColor else lineNumberColor, (if (thumbDrag) 0.9f else 0.6f) * alpha)
                Fill.crect(x + width - w - Scl.scl(1f), y + ty, w, h)
            }
        }
        Draw.reset()
    }

    /**
     * 行顶到 [Font.draw] 的 y 要往下挪多少：和 Label 的 CSS 行盒一样，字形（上伸 + 下伸）在行高里上下居中，
     * 不然字贴着行顶、下面空一截，和当前行底色、光标对不齐。
     */
    private val textDrop: Float get() = dropOf(font)

    private fun dropOf(f: Font): Float {
        val d = f.data
        if (d.cssAscent + d.cssDescent <= 0f) return 0f
        return (lh - (d.cssAscent + d.cssDescent)) / 2f + d.cssAscent - d.capHeight
    }

    private fun xoff(c: Char): Float = (font.data.getGlyph(c)?.xoffset ?: 0) * font.data.scaleX

    private fun lowerBound(a: FloatArray, x: Float): Int {
        var lo = 0; var hi = a.size - 1
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (a[mid + 1] <= x) lo = mid + 1 else hi = mid }
        return lo
    }

    private fun drawRange(s: Pos, e: Pos, first: Int, last: Int, top: Float, left: Float, c: Color, alpha: Float) {
        Draw.color(c, c.a * alpha)
        for (l in max(s.line, first)..min(e.line, last)) {
            val len = doc.line(l).length
            val c0 = if (l == s.line) s.col.coerceAtMost(len) else 0
            val c1 = if (l == e.line) e.col.coerceAtMost(len) else len + 1
            segments(l, c0, c1, extendEnd = true) { row, x0, x1 -> if (x1 > x0) Fill.crect(left + x0, top - (row + 1) * lh, x1 - x0, lh) }
        }
    }

    private fun drawMatches(first: Int, last: Int, top: Float, left: Float, alpha: Float) {
        var lo = 0; var hi = matches.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (matches[mid].second.line < first) lo = mid + 1 else hi = mid }
        var k = lo
        val c = Color()
        while (k < matches.size && matches[k].first.line <= last) {
            c.set(matchColor).a = if (k == currentMatch) 0.55f else 0.22f
            drawRange(matches[k].first, matches[k].second, first, last, top, left, c, alpha)
            k++
        }
    }

    private fun markLevels(): Map<Int, Int> {
        val levels = HashMap<Int, Int>(lineMarks)
        for (m in marks) levels[m.line] = max(levels[m.line] ?: 0, m.level)
        return levels
    }

    /** 问题条上第 [l] 行那一段：返回（本地坐标的上沿 y, 高度）。 */
    private fun stripeOf(l: Int, maxY: Float = maxScrollY): Pair<Float, Float> {
        if (maxY <= 0f) {
            // 一屏放得下：就画在那一行旁边（随滚动走）
            val top = height - padTop + scrollY - rowOf(l) * lh
            return top - Scl.scl(2f) to lh * rowsIn(l) - Scl.scl(4f)
        }
        val total = maxY + height
        val h = max(Scl.scl(3f), lh * rowsIn(l) * height / total)
        val top = height - (padTop + rowOf(l) * lh) / total * height
        return top.coerceIn(h, height) to h
    }

    /** 滚动条滑块：（本地坐标下沿 y, 高度）。 */
    private fun thumbRect(): Pair<Float, Float> {
        val content = maxScrollY + height
        val h = max(Scl.scl(36f), height * height / content)
        return height - h - (height - h) * (scrollY / max(1f, maxScrollY)) to h
    }

    /** 按住右边缘拖动：手指位置直接对应全文位置（快速滚动）。 */
    private var thumbDrag = false
    private var thumbMoved = false
    private var thumbGrab = 0f

    private fun thumbTo(ly: Float) {
        val (_, h) = thumbRect()
        val track = max(1f, height - h)
        val t = ((height - h - (ly - thumbGrab)) / track).coerceIn(0f, 1f)
        scrollY = t * maxScrollY
        vy = 0f
        clampScroll()
    }

    /** 点问题条：跳到点的位置附近最近的问题行，返回是否跳了。 */
    private fun stripeTap(ly: Float): Boolean {
        val levels = markLevels()
        if (levels.isEmpty()) return false
        var best = -1; var bestD = Float.MAX_VALUE
        for (l in levels.keys) {
            val (top, h) = stripeOf(l)
            val d = abs(ly - (top - h / 2f))
            if (d < bestD) { bestD = d; best = l }
        }
        if (best < 0 || bestD > Scl.scl(24f)) return false
        val col = marks.filter { it.line == best }.minOfOrNull { it.start } ?: 0
        hideMenu()
        setCursor(Pos(best, col))
        revealCursor()
        return true
    }

    /** IDEA 的错误波浪线：在 [bottom] 往上 2dp 的带子里画锯齿。 */
    private fun wave(x0: Float, bottom: Float, w: Float, c: Color, alpha: Float) {
        Draw.color(c, alpha)
        val step = Scl.scl(2f); val amp = Scl.scl(1.6f); val t = Scl.scl(1f)
        var px = x0
        var up = true
        while (px < x0 + w) {
            val nx = min(px + step, x0 + w)
            val y0 = if (up) bottom else bottom + amp
            val y1 = if (up) bottom + amp else bottom
            Lines.stroke(t)
            Lines.line(px, y0, nx, y1)
            px = nx; up = !up
        }
    }

    private fun markRect(m: Mark, top: Float, left: Float, c: Color, alpha: Float) {
        val len = doc.line(m.line).length
        val s0 = m.start.coerceIn(0, len); val e0 = m.end.coerceIn(s0, len)
        if (e0 <= s0) return
        Draw.color(c, c.a * alpha)
        segments(m.line, s0, e0) { row, x0, x1 -> Fill.crect(left + x0, top - (row + 1) * lh, x1 - x0, lh) }
    }

    /** 缩进参考线：每级缩进（4 格或 1 个 Tab）一条竖线；空行沿用上下文的缩进。 */
    private fun drawIndentGuides(first: Int, last: Int, top: Float, left: Float, alpha: Float) {
        val unit = advance(' ') * 4
        if (unit <= 0f) return
        Draw.color(indentGuideColor, indentGuideColor.a * alpha)
        var prev = 0
        for (l in first..last) {
            val line = doc.line(l)
            var cols = 0
            var i = 0
            while (i < line.length && (line[i] == ' ' || line[i] == '\t')) { cols += if (line[i] == '\t') 4 else 1; i++ }
            val levels = if (i == line.length) prev else cols / 4
            if (i < line.length) prev = levels
            val rows = rowsIn(l)
            for (k in 1 until levels) {
                Fill.crect(left + unit * k, top - (rowOf(l) + rows) * lh, Scl.scl(1f), lh * rows)
            }
        }
    }

    /** 光标停在标识符上时，把可见范围内同名的地方铺浅底（IDEA 的 Identifier under caret）。 */
    private fun drawUsages(first: Int, last: Int, top: Float, left: Float, alpha: Float) {
        if (cursor.line !in 0 until doc.lineCount) return
        val line = doc.line(cursor.line)
        fun word(c: Char) = c.isLetterOrDigit() || c == '_'
        var s = cursor.col.coerceIn(0, line.length); var e = s
        while (s > 0 && word(line[s - 1])) s--
        while (e < line.length && word(line[e])) e++
        if (e - s < 2) return
        val w = line.substring(s, e)
        if (w.all { it.isDigit() }) return
        Draw.color(usageColor, usageColor.a * alpha)
        for (l in first..last) {
            val t = doc.line(l)
            var i = t.indexOf(w)
            while (i >= 0) {
                val before = i == 0 || !word(t[i - 1]); val after = i + w.length >= t.length || !word(t[i + w.length])
                if (before && after) {
                    segments(l, i, i + w.length) { row, x0, x1 -> Fill.crect(left + x0, top - (row + 1) * lh, x1 - x0, lh) }
                }
                i = t.indexOf(w, i + w.length)
            }
        }
    }

    private fun handleCenter(p: Pos, top: Float, left: Float): Vec2 =
        tmp.set(left + xOf(p), top - (rowOfPos(p) + 1) * lh - Scl.scl(10f))

    private fun drawHandles(top: Float, left: Float, alpha: Float) {
        Draw.color(handleColor, alpha)
        // 水滴形（安卓原生样式）：圆 + 朝选区一侧的直角，尖角正对选区端点
        val r = Scl.scl(11f)
        listOf(selectionStart, selectionEnd).forEachIndexed { i, p ->
            val c = handleCenter(p, top, left)
            val cx = if (i == 0) c.x - r else c.x + r
            val cy = c.y + Scl.scl(10f) - r
            arc.graphics.g2d.Shapes.rect(cx - r, cy - r, r * 2, r * 2, r, Color(handleColor).a(alpha))
            Draw.color(handleColor, alpha)
            if (i == 0) Fill.crect(cx, cy, r, r) else Fill.crect(cx - r, cy, r, r)
        }
    }

    // ================= 选区菜单 =================

    /** 选区菜单换成图标按钮（宿主提供图标，名字给测试脚本按）。 */
    fun setMenuItems(items: List<MenuItem>) {
        menu.clearChildren()
        for (it in items) {
            val cell = Table()
            cell.name = it.name
            cell.touchable = Touchable.enabled
            cell.add(it.icon)
            cell.clicked { it.action(); if (!it.keepOpen) hideMenu() }
            menu.add(cell).size(Scl.scl(44f))
        }
        menu.margin(Scl.scl(2f), Scl.scl(4f), Scl.scl(2f), Scl.scl(4f))
    }

    class MenuItem(val name: String, val icon: Element, val keepOpen: Boolean = false, val action: () -> Unit)

    private fun buildMenu() {
        menu.background = RwTheme.round(12, RwTheme.Colors.surface3)
        menu.touchable = Touchable.childrenOnly
        menu.visible = false
        fun item(label: String, action: () -> Unit) {
            val l = Label(label, Label.LabelStyle(RwTheme.text(14f, 500), RwTheme.Colors.text))
            l.name = label
            l.touchable = Touchable.enabled
            l.clicked { action() }
            menu.add(l).pad(Scl.scl(10f), Scl.scl(12f), Scl.scl(10f), Scl.scl(12f))
        }
        item("全选") { selectAll() }
        item("复制") { copy(); hideMenu() }
        item("剪切") { cut(); hideMenu() }
        item("粘贴") { paste(); hideMenu() }
        addChild(menu)
    }

    private fun showMenu() {
        if (!menu.visible) {
            menu.clearActions()
            menu.color.a = 0f
            menu.actions(arc.scene.actions.Actions.fadeIn(0.12f, arc.math.Interp.smooth))
        }
        menu.visible = true; menu.pack()
    }
    private fun hideMenu() { menu.visible = false }
    private fun hideMenuIfEmpty() { if (!hasSelection) hideMenu() }

    private fun layoutMenu() {
        if (!menu.visible) return
        if (!hasSelection && !longPressed) { /* 只有「粘贴」用途时也允许显示 */ }
        menu.pack()
        val p = selectionStart
        val top = height - padTop + scrollY
        val mx = (textLeft - scrollX + xOf(p) - menu.width / 2).coerceIn(Scl.scl(4f), max(Scl.scl(4f), width - menu.width - Scl.scl(4f)))
        var my = top - rowOfPos(p) * lh + Scl.scl(8f)
        if (my + menu.height > height) my = top - (rowOfPos(selectionEnd) + 1) * lh - Scl.scl(28f) - menu.height
        menu.setPosition(mx, my.coerceIn(0f, max(0f, height - menu.height)))
    }

    fun copy() { if (hasSelection) Core.app.clipboardText = selectedText }
    fun cut() { if (hasSelection && editable) { copy(); insert("", origin = Edit.Origin.KEY) } }
    fun paste() { val t = Core.app.clipboardText ?: return; if (editable) insert(t, origin = Edit.Origin.KEY) }

    private fun selectWordAt(p: Pos) {
        val line = doc.line(p.line)
        fun word(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.' || c == '-'
        var s = p.col; var e = p.col
        while (s > 0 && word(line[s - 1])) s--
        while (e < line.length && word(line[e])) e++
        if (s == e && e < line.length) e++
        select(Pos(p.line, s), Pos(p.line, e), handles = true)
        showMenu()
    }

    // ================= 编辑（按键） =================

    private fun type(text: String) {
        if (!editable) return
        insert(text, origin = Edit.Origin.KEY)
        hideMenu()
    }

    /** 回车：保持上一行的缩进。 */
    private fun newline() {
        if (onEnter?.invoke() == true) return
        val line = doc.line(cursor.line)
        val indent = line.takeWhile { it == ' ' || it == '\t' }.take(cursor.col)
        type("\n" + indent)
    }

    private fun backspace() {
        if (!editable) return
        if (hasSelection) { type(""); return }
        if (cursor == Pos(0, 0)) return
        val from = if (cursor.col > 0) Pos(cursor.line, cursor.col - 1) else Pos(cursor.line - 1, doc.line(cursor.line - 1).length)
        val end = doc.replace(from, cursor, "", Edit.Origin.KEY, cursor)
        cursor = end; anchor = null
        ensureCursorVisible()
    }

    private fun deleteForward() {
        if (!editable) return
        if (hasSelection) { type(""); return }
        val to = if (cursor.col < doc.line(cursor.line).length) Pos(cursor.line, cursor.col + 1)
            else if (cursor.line < doc.lineCount - 1) Pos(cursor.line + 1, 0) else return
        doc.replace(cursor, to, "", Edit.Origin.KEY, cursor)
        ensureCursorVisible()
    }

    private fun move(p: Pos, select: Boolean, keepPreferred: Boolean = false) {
        val px = preferredX
        setCursor(p, keepAnchor = select)
        if (keepPreferred) preferredX = px
        doc.seal()
        ensureCursorVisible()
    }

    private fun vertical(dl: Int, select: Boolean) {
        if (preferredX < 0f) preferredX = xOf(cursor)
        // 按视觉行上下移（自动换行时在折成的段之间走）
        val r = (rowOfPos(cursor) + dl).coerceIn(0, max(0, totalRows - 1))
        val l = lineAtRow(r)
        val seg = r - rowOf(l)
        val text = doc.line(l)
        val a = xs(text)
        val st = segsOf(l)
        val sh = segShift(a, st, seg)
        val se = if (seg + 1 < st.size) st[seg + 1] - 1 else text.length
        var col = st[seg]
        while (col < se && (a[col] + a[col + 1]) / 2 - sh < preferredX) col++
        move(Pos(l, col), select, keepPreferred = true)
    }

    // 惯性：记最近 100ms 的滚动位置，松手时按这段时间的平均速度算（单帧的瞬时速度抖得厉害，会突然飞得很快）
    private val samples = ArrayDeque<FloatArray>()

    // 时间记成「按下以来的毫秒数」：以前直接把 currentTimeMillis（约 1.8e12）转成 Float，
    // 精度只剩约 13 万毫秒，采样点的时间几乎全一样——速度要么按 16ms 算（稍微挪一下就飞出去），要么完全不给，时好时坏
    private fun track(now: Long) {
        val t = (now - downAt).toFloat()
        samples.addLast(floatArrayOf(t, scrollX, scrollY))
        while (samples.size > 2 && t - samples.first()[0] > 100f) samples.removeFirst()
    }

    /**
     * 松手时的惯性：按最近 100ms 的平均速度算（开始拖动那一刻记了起点，再快的一划也至少两个采样点；
     * 时间差最少按 8ms 算，手机触屏事件间隔 8～16ms）。不给惯性的情况：
     * - 松手前停了 60ms 以上；
     * - 整次拖动不到 24dp、或速度低于 300dp/s（就是轻轻挪了一下，不该再自己滑）。
     */
    private fun flingVelocity() {
        vx = 0f; vy = 0f
        val now = (System.currentTimeMillis() - downAt).toFloat()
        if (samples.size < 2 || now - samples.last()[0] > 60f) return
        val a = samples.first(); val b = samples.last()
        val span = b[0] - a[0]
        if (span <= 0f) return
        if (arc.math.Mathf.dst(downX, downY, lastX, lastY) < Scl.scl(24f)) return
        val dt = max(span, 8f) / 1000f
        val cap = Scl.scl(6000f)
        val fx = ((b[1] - a[1]) / dt).coerceIn(-cap, cap)
        val fy = ((b[2] - a[2]) / dt).coerceIn(-cap, cap)
        if (kotlin.math.hypot(fx, fy) < Scl.scl(300f)) return
        vx = fx; vy = fy
    }

    // 双指缩放
    private var pinching = false
    private var p0x = 0f; private var p0y = 0f; private var p1x = 0f; private var p1y = 0f
    private var pinchDist = 0f; private var pinchSize = 0f

    /**
     * 缩放中：不重新生成字形（每档都生成会卡），用一套大字号的字缩放显示；松手时再换成这个字号的清晰字。
     */
    private var previewSize = 0f

    private fun fontAtZoom(z: Float): Font =
        if (z < RASTER_MIN_ZOOM) scaledZoomFont(z) else RwTheme.monoAt(z, z * LINE_RATIO)

    /** 始终从正常字号的图集缩放，不能把不足一个像素的字号传给 FreeType。 */
    private fun scaledZoomFont(z: Float): Font {
        val rasterSize = if (z <= 14f) 14f else MAX_ZOOM
        val master = RwTheme.monoMaster(rasterSize)
        val base = masterBase.getOrPut(master) { floatArrayOf(master.data.cssAscent, master.data.cssDescent, master.data.scaleX, Scl.sp(rasterSize)) }
        val k = Scl.sp(z) / base[3]
        val scale = base[2] * k
        val lineHeight = Scl.sp(z * LINE_RATIO)
        if (master.data.scaleX != scale || master.data.lineHeight != lineHeight) {
            master.data.setScale(scale)
            master.data.cssAscent = base[0] * k
            master.data.cssDescent = base[1] * k
            master.data.lineHeight = lineHeight
            master.data.cssLineHeight = lineHeight
        }
        master.setUseIntegerPositions(false)
        return master
    }

    private fun previewZoom(z0: Float) {
        val z = z0.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (z == previewSize || (z >= RASTER_MIN_ZOOM && abs(z - previewSize) < 0.05f)) return
        previewSize = z
        font = scaledZoomFont(z)
    }

    private fun endZoom() {
        val z = normalizeZoom(previewSize)
        previewSize = 0f
        if (z == fontSize) font = fontAtZoom(z) else fontSize = z
        clampScroll()
    }

    private fun pinchUpdate() {
        val d = arc.math.Mathf.dst(p0x, p0y, p1x, p1y)
        if (pinchDist < 1f) return
        val before = lh
        // 以两指中点所在的行为锚点缩放，缩放前后这行停在原处
        val fy = height - padTop - (p0y + p1y) / 2f
        val fx = (p0x + p1x) / 2f - textLeft
        val anchorY = (scrollY + fy) / before
        val oldAdv = advance('0')
        val anchorX = (scrollX + fx) / oldAdv.coerceAtLeast(0.000001f)
        previewZoom(pinchSize * d / pinchDist)
        if (lh != before) {
            scrollY = anchorY * lh - fy
            scrollX = anchorX * advance('0') - fx
            clampScroll()
        }
    }

    private inner class Input : InputListener() {
        override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: KeyCode): Boolean {
            // 补全弹窗、选区菜单是编辑器的子控件：点它们时事件也会冒泡到这里，不能当成点正文（否则光标跑到点的位置）
            if (event.targetActor !== this@CodeEditor) return false
            if (pointer == 1 && pressed) {
                pinching = true; moved = true; longPressed = false; dragHandle = 0
                vx = 0f; vy = 0f
                p0x = lastX; p0y = lastY; p1x = x; p1y = y
                pinchDist = arc.math.Mathf.dst(p0x, p0y, p1x, p1y); pinchSize = fontSize; previewSize = 0f
                hideMenu()
                return true
            }
            if (pointer != 0) return false
            // 右边缘：拖动是快速滚动（按在滑块上就带着滑块走，按在别处滑块先跳过来），不动抬起再看是不是点了问题条
            // 只有按在滑块上才拖滑块：以前按在右边缘 20dp 任何位置都算，稍微一动文档就跳到手指对应的位置
            if (x > width - Scl.scl(20f) && maxScrollY > 0f) {
                val (ty, h) = thumbRect()
                val slop = Scl.scl(8f)
                if (y in ty - slop..ty + h + slop) {
                    thumbGrab = y - ty - h / 2f
                    thumbDrag = true; thumbMoved = false; downY = y
                    hideMenu()
                    return true
                }
            }
            if (x > width - Scl.scl(16f) && stripeTap(y)) return false
            pressed = true; moved = false; longPressed = false
            downX = x; downY = y; lastX = x; lastY = y
            downAt = System.currentTimeMillis(); lastMoveAt = downAt
            samples.clear()
            vx = 0f; vy = 0f; axisLock = 0
            dragHandle = 0
            if (hasSelection && touchMode) {
                val top = height - padTop + scrollY
                val left = textLeft - scrollX
                val r = Scl.scl(22f)
                handleCenter(selectionStart, top, left).let { if (it.dst(x, y) < r) dragHandle = 1 }
                if (dragHandle == 0) handleCenter(selectionEnd, top, left).let { if (it.dst(x, y) < r) dragHandle = 2 }
            }
            return true
        }

        override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
            if (thumbDrag && pointer == 0) {
                if (!thumbMoved && abs(y - downY) > Scl.scl(4f)) thumbMoved = true
                if (thumbMoved) thumbTo(y)
                return
            }
            if (pinching) {
                if (pointer == 0) { p0x = x; p0y = y; lastX = x; lastY = y } else if (pointer == 1) { p1x = x; p1y = y }
                pinchUpdate()
                return
            }
            if (pointer != 0) return
            val now = System.currentTimeMillis()
            if (dragHandle != 0) {
                val p = posAt(x, y + Scl.scl(18f))
                if (dragHandle == 1) { val e = selectionEnd; select(minOf(p, e), e, handles = true) } else { val s = selectionStart; select(s, maxOf(p, s), handles = true) }
                cursor.let { ensureCursorVisible() }
                lastX = x; lastY = y
                return
            }
            if (!moved && arc.math.Mathf.dst(downX, downY, x, y) > Scl.scl(10f)) {
                moved = true
                // 起点：快速一划从越过阈值到松手可能只有一两个事件，没有起点就算不出速度（松手直接停住）
                track(now)
                axisLock = if (abs(x - downX) > abs(y - downY) * 2f) 1 else if (abs(y - downY) > abs(x - downX) * 2f) 2 else 0
            }
            if (!moved || longPressed) { lastX = x; lastY = y; return }
            val dx = if (axisLock == 2) 0f else x - lastX
            val dy = if (axisLock == 1) 0f else y - lastY
            scrollX -= dx; scrollY += dy
            clampScroll()
            track(now)
            lastX = x; lastY = y; lastMoveAt = now
        }

        override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: KeyCode) {
            if (thumbDrag && pointer == 0) {
                thumbDrag = false
                if (!thumbMoved) stripeTap(y)
                return
            }
            if (pinching) {
                // 抬起任意一指就结束缩放；剩下那根手指不再滚动，抬起也不算点按
                pinching = false
                if (previewSize > 0f) endZoom()
                if (pointer == 0) pressed = false
                vx = 0f; vy = 0f
                return
            }
            if (pointer != 0) { return }
            pressed = false
            if (dragHandle != 0) { dragHandle = 0; showMenu(); if (focused) syncPending = true; return }
            if (moved) {
                flingVelocity()
                return
            }
            vx = 0f; vy = 0f
            if (longPressed) return
            val p = posAt(x, y)
            val now = System.currentTimeMillis()
            val double = now - lastTapAt < 320 && lastTapPos?.line == p.line && abs((lastTapPos?.col ?: 0) - p.col) <= 2
            lastTapAt = now; lastTapPos = p
            touchMode = true
            if (double) { selectWordAt(p); return }
            if (!double && onTapAt?.invoke(p) == true) { lastTapAt = 0; return }
            hideMenu()
            doc.seal()
            setCursor(p)
            focusAndShowKeyboard()
        }

        override fun scrolled(event: InputEvent, x: Float, y: Float, amountX: Float, amountY: Float): Boolean {
            if (event.targetActor !== this@CodeEditor) return false
            if (Core.input.ctrl()) {
                fontSize = if (fontSize < RASTER_MIN_ZOOM) fontSize * exp(-amountY * 0.1f) else fontSize - amountY * 0.5f
                return true
            }
            scrollY += amountY * lh * 3; scrollX += amountX * lh * 3
            clampScroll()
            return true
        }

        override fun keyDown(event: InputEvent, keycode: KeyCode): Boolean {
            if (!focused) return false
            val shift = Core.input.shift()
            touchMode = false
            if (Core.input.ctrl()) {
                when (keycode) {
                    KeyCode.a -> selectAll()
                    KeyCode.c -> copy()
                    KeyCode.x -> cut()
                    KeyCode.v -> paste()
                    KeyCode.z -> if (shift) redo() else undo()
                    KeyCode.y -> redo()
                    KeyCode.s -> onSave?.invoke()
                    KeyCode.home -> move(Pos(0, 0), shift)
                    KeyCode.end -> move(doc.end(), shift)
                    else -> return false
                }
                return true
            }
            when (keycode) {
                KeyCode.left -> move(if (hasSelection && !shift) selectionStart else if (cursor.col > 0) Pos(cursor.line, cursor.col - 1) else if (cursor.line > 0) Pos(cursor.line - 1, doc.line(cursor.line - 1).length) else cursor, shift)
                KeyCode.right -> move(if (hasSelection && !shift) selectionEnd else if (cursor.col < doc.line(cursor.line).length) Pos(cursor.line, cursor.col + 1) else if (cursor.line < doc.lineCount - 1) Pos(cursor.line + 1, 0) else cursor, shift)
                KeyCode.up -> vertical(-1, shift)
                KeyCode.down -> vertical(1, shift)
                KeyCode.pageUp -> vertical(-(height / lh).toInt(), shift)
                KeyCode.pageDown -> vertical((height / lh).toInt(), shift)
                KeyCode.home -> {
                    val indent = doc.line(cursor.line).takeWhile { it == ' ' || it == '\t' }.length
                    move(Pos(cursor.line, if (cursor.col == indent) 0 else indent), shift)
                }
                KeyCode.end -> move(Pos(cursor.line, doc.line(cursor.line).length), shift)
                KeyCode.forwardDel -> deleteForward()
                else -> return false
            }
            return true
        }

        override fun keyTyped(event: InputEvent, character: Char): Boolean {
            if (!focused || Core.input.ctrl()) return false
            touchMode = false
            when (character) {
                '\b' -> backspace()
                '\u007f' -> deleteForward()
                '\r', '\n' -> newline()
                '\t' -> if (onTab?.invoke() != true) type(if (cursor.col <= doc.line(cursor.line).takeWhile { it == ' ' }.length) "    " else "\t")
                else -> if (character >= ' ') type(character.toString()) else return false
            }
            return true
        }
    }

    // ================= 输入法（窗口） =================

    /** 交给输入法的是光标前后各约 30 行（总长不超过 6000 字），大文件也只传这一小段。 */
    override fun imeText(): String {
        var s = max(0, cursor.line - 30)
        var e = min(doc.lineCount - 1, cursor.line + 30)
        fun size(): Int { var n = 0; for (l in s..e) n += doc.line(l).length + 1; return n }
        while (size() > 6000 && (s < cursor.line || e > cursor.line)) { if (cursor.line - s > e - cursor.line) s++ else e-- }
        imeStartLine = s; imeEndLine = e
        val sb = StringBuilder()
        for (l in s..e) { if (l > s) sb.append('\n'); sb.append(doc.line(l)) }
        imeTextCache = sb.toString()
        return imeTextCache
    }

    private fun toWindow(p: Pos): Int {
        if (p.line < imeStartLine) return 0
        if (p.line > imeEndLine) return imeTextCache.length
        var o = 0
        for (l in imeStartLine until p.line) o += doc.line(l).length + 1
        return o + p.col
    }

    override fun imeSelectionStart(): Int = toWindow(selectionStart)
    override fun imeSelectionEnd(): Int = toWindow(selectionEnd)
    override fun imeMultiline(): Boolean = true
    override fun imePassword(): Boolean = false

    private fun fromWindow(text: String, offset: Int): Pos {
        val o = offset.coerceIn(0, text.length)
        var line = imeStartLine; var ls = 0
        var i = text.indexOf('\n')
        while (i in 0 until o) { line++; ls = i + 1; i = text.indexOf('\n', i + 1) }
        return Pos(line, o - ls)
    }

    override fun imeUpdate(text: String, selStart: Int, selEnd: Int, compStart: Int, compEnd: Int) {
        val old = imeTextCache
        var autoIndent: String? = null
        if (text != old && editable) {
            var p = 0
            val lim = min(old.length, text.length)
            while (p < lim && old[p] == text[p]) p++
            var q = 0
            while (q < lim - p && old[old.length - 1 - q] == text[text.length - 1 - q]) q++
            val a = fromWindow(old, p)
            val b = fromWindow(old, old.length - q)
            val inserted = text.substring(p, text.length - q)
            val removed = old.substring(p, old.length - q)
            // 输入法的回车：先问宿主（补全开着就接受补全），接受了就不换行，重新同步输入法
            if (inserted == "\n" && removed.isEmpty() && compStart < 0 && onEnter?.invoke() == true) {
                syncPending = true
                ensureCursorVisible()
                return
            }
            doc.replace(a, b, inserted, Edit.Origin.IME, cursor)
            imeEndLine += inserted.count { it == '\n' } - removed.count { it == '\n' }
            imeTextCache = text
            if (inserted == "\n" && compStart < 0) {
                autoIndent = doc.line(a.line).takeWhile { it == ' ' || it == '\t' }.take(a.col).ifEmpty { null }
            }
        }
        val s = fromWindow(text, min(selStart, selEnd))
        val e = fromWindow(text, max(selStart, selEnd))
        cursor = doc.clamp(e)
        anchor = if (s != e) doc.clamp(s) else null
        composing = if (compStart >= 0 && compEnd > compStart) doc.clamp(fromWindow(text, compStart)) to doc.clamp(fromWindow(text, compEnd)) else null
        preferredX = -1f
        blink = 0f
        touchMode = true
        hideMenuIfEmpty()
        if (autoIndent != null) {
            // 回车后补上一行的缩进（程序改动，随后同步给输入法）
            insert(autoIndent, origin = Edit.Origin.KEY)
        } else if (composing == null && (cursor.line - imeStartLine < 6 && imeStartLine > 0 || imeEndLine - cursor.line < 6 && imeEndLine < doc.lineCount - 1)) {
            // 光标走到窗口边上：重新取一段
            syncPending = true
        }
        ensureCursorVisible()
    }

    /**
     * 软键盘的「换行」：不少输入法发的是回车键事件，安卓桥会拦下来转成这里（不会再往隐藏输入框里写 \n），
     * 原来是空实现，手机上回车完全没反应。走和实体键盘一样的回车：补全开着先接受，否则换行并保持缩进。
     */
    override fun imeAction() {
        if (!editable) return
        composing = null
        newline()
        syncPending = true
        ensureCursorVisible()
    }

    /** 一处问题：[line] 行 [start]..[end) 列，[level] 2 错误 / 1 警告。 */
    data class Mark(val line: Int, val start: Int, val end: Int, val level: Int)

    companion object {
        const val DEFAULT_ZOOM = 13f
        const val MIN_ZOOM = DEFAULT_ZOOM / 1000f
        const val MAX_ZOOM = 28f
        private const val RASTER_MIN_ZOOM = 8f
        /** 行高低于这么多 dp 就改用缩略图画法。 */
        private const val OVERVIEW_LINE_HEIGHT = 1.5f
        internal fun normalizeZoom(size: Float): Float =
            (if (size < RASTER_MIN_ZOOM) size else Math.round(size * 2f) / 2f).coerceIn(MIN_ZOOM, MAX_ZOOM)
        private const val LINE_RATIO = 20f / 13f
        /** 行号离行号栏两边的距离（dp）。 */
        private const val GUTTER_PAD = 5f
        /** 上次缩放到的字号，新开的编辑器沿用。 */
        @JvmStatic var zoom = DEFAULT_ZOOM
        /** 自动换行开关，新开的编辑器沿用。 */
        @JvmStatic var wrapDefault = false
        private val masterBase = HashMap<Font, FloatArray>()
    }
}
