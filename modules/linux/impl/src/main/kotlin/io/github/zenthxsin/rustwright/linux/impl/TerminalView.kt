package io.github.zenthxsin.rustwright.linux.impl

import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.graphics.g2d.Font
import arc.input.KeyCode
import arc.scene.Element
import arc.scene.event.InputEvent
import arc.scene.event.InputListener
import arc.graphics.g2d.Shapes
import arc.math.Mathf
import arc.scene.ui.layout.Scl
import arc.util.Align
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import io.github.zenthxsin.rustwright.core.terminal.PtySession
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme

/**
 * Arc 终端控件：按单元格绘制 [PtySession] 的屏幕（256 色 / 真彩、粗体、反显、下划线、光标），
 * 尺寸变化时同步 PTY 窗口大小；上下拖动查看回滚历史。等宽字体缺字（如中文）时用正文字体补画。
 * 长按选词，出现蓝色选区和两个水滴拖柄（参考 Sora 编辑器），拖柄可调整范围；点一下空白处取消。
 * 选区菜单（复制 / 全选 / 粘贴）由外层页面按 [selectionBounds] 摆放。
 */
class TerminalView(var session: PtySession?, private val mono: Font = RwTheme.mono) : Element() {

    /** 底色（融合版终端用原型的 #0B0B0A）。 */
    var background: Color = TERMINAL_BG
    private val fallback: Font = RwTheme.font
    val cellWidth: Float = mono.data.getGlyph('M')?.xadvance?.toFloat()?.takeIf { it > 0 } ?: (mono.lineHeight * 0.6f)
    val cellHeight: Float = mono.lineHeight
    /** 回滚偏移：0 为最底部，负数为向上翻的行数。 */
    private var topRow = 0
    private var dragAccum = 0f
    private val sb = StringBuilder(4)
    private val fg = Color()
    private val bg = Color()

    var columns = 0; private set
    var rows = 0; private set

    // ---- 选择（外部行号：0 为屏幕首行，负数为回滚历史；起点不晚于终点） ----
    private var selActive = false
    private var sCol = 0
    private var sRow = 0
    private var eCol = 0
    private var eRow = 0
    private var pressed = false
    private var moved = false
    private var longPressed = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    /** 正在拖的拖柄：1 起点，2 终点。 */
    private var dragHandle = 0
    private val handleColor = Color(0.25f, 0.55f, 0.98f, 1f)
    private val selColor = Color(0.25f, 0.55f, 0.98f, 0.42f)
    private val hr get() = Scl.scl(11f)

    /** 选区出现、变化、消失，或拖柄松手时调用（外层据此显示 / 隐藏菜单）。 */
    var onSelectionChanged: () -> Unit = {}
    val hasSelection get() = selActive
    /** 正在拖拖柄时不显示菜单。 */
    val draggingHandle get() = dragHandle != 0

    init {
        touchable = arc.scene.event.Touchable.enabled
        addListener(object : InputListener() {
            var lastY = 0f
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: KeyCode): Boolean {
                if (pointer != 0) return false
                lastY = y; dragAccum = 0f
                pressed = true; moved = false; longPressed = false
                downX = x; downY = y; downAt = System.currentTimeMillis()
                dragHandle = if (selActive) hitHandle(x, y) else 0
                if (dragHandle != 0) onSelectionChanged()
                return true
            }

            override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                if (pointer != 0) return
                if (dragHandle != 0) { dragHandleTo(x, y); return }
                if (!moved && Mathf.dst(downX, downY, x, y) > Scl.scl(10f)) moved = true
                if (longPressed || !moved) { lastY = y; return }
                dragAccum += y - lastY
                lastY = y
                // 手指往上推，内容跟着往上走（看更新的内容）；往下拉翻回历史，和普通列表一致
                val lines = (dragAccum / cellHeight).toInt()
                if (lines != 0) { scrollBy(lines); dragAccum -= lines * cellHeight }
            }

            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: KeyCode) {
                if (pointer != 0) return
                pressed = false
                if (dragHandle != 0) { dragHandle = 0; onSelectionChanged(); return }
                if (!moved && !longPressed && selActive) clearSelection()
            }
        })
    }

    override fun act(delta: Float) {
        super.act(delta)
        // 长按 0.45 秒不动：选中手指下的词
        if (pressed && !moved && !longPressed && dragHandle == 0 && session != null && System.currentTimeMillis() - downAt > 450) {
            longPressed = true
            selectWordAt(downX, downY)
        }
    }

    /** 控件内坐标 → (列, 外部行)，夹在有效范围内。 */
    private fun cellClamped(lx: Float, ly: Float): Pair<Int, Int> {
        val emu = session?.emulator ?: return 0 to 0
        val col = Mathf.floor(lx / cellWidth).coerceIn(0, emu.mColumns - 1)
        val row = (Mathf.floor((height - ly) / cellHeight) + topRow).coerceIn(-emu.screen.activeTranscriptRows, emu.mRows - 1)
        return col to row
    }

    private fun blankAt(col: Int, ext: Int): Boolean {
        val screen = session?.emulator?.screen ?: return true
        val row = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(ext))
        val i = row.findStartOfColumn(col)
        if (i >= row.spaceUsed) return true
        return Character.isWhitespace(Character.codePointAt(row.mText, i))
    }

    private fun selectWordAt(lx: Float, ly: Float) {
        val emu = session?.emulator ?: return
        val (c, r) = cellClamped(lx, ly)
        var l = c
        var rt = c
        if (!blankAt(c, r)) {
            while (l > 0 && !blankAt(l - 1, r)) l--
            while (rt < emu.mColumns - 1 && !blankAt(rt + 1, r)) rt++
        }
        setSelection(l, r, rt, r)
    }

    private fun setSelection(c1: Int, r1: Int, c2: Int, r2: Int) {
        val swap = r2 < r1 || r2 == r1 && c2 < c1
        if (swap) { sCol = c2; sRow = r2; eCol = c1; eRow = r1 } else { sCol = c1; sRow = r1; eCol = c2; eRow = r2 }
        selActive = true
        onSelectionChanged()
    }

    /** 全选：回滚历史 + 当前屏幕。 */
    fun selectAll() {
        val emu = session?.emulator ?: return
        setSelection(0, -emu.screen.activeTranscriptRows, emu.mColumns - 1, emu.mRows - 1)
    }

    fun clearSelection() {
        if (!selActive) return
        selActive = false
        dragHandle = 0
        onSelectionChanged()
    }

    /** 选中的纯文本（保留中文与缩进，行尾空白去掉）。 */
    fun selectionText(): String {
        val s = session ?: return ""
        if (!selActive) return ""
        return s.emulator.screen.getSelectedText(sCol, sRow, eCol, eRow)
    }

    /** 选区在控件内的可见范围：左、上、右、下；完全滚出视野时为 null。 */
    fun selectionBounds(): FloatArray? {
        if (!selActive) return null
        val firstVisible = sRow.coerceAtLeast(topRow)
        val lastVisible = eRow.coerceAtMost(topRow + rows - 1)
        if (lastVisible < firstVisible) return null
        val top = height - (firstVisible - topRow) * cellHeight
        val bottom = height - (lastVisible - topRow + 1) * cellHeight
        val left = if (sRow == eRow) sCol * cellWidth else 0f
        val right = if (sRow == eRow) (eCol + 1) * cellWidth else columns * cellWidth
        return floatArrayOf(left, top, right, bottom)
    }

    /** 起点拖柄：水滴尖角朝右上，顶在起点格左下角；终点拖柄朝左上，顶在终点格右下角。 */
    private fun handleAnchor(start: Boolean): Pair<Float, Float> =
        if (start) sCol * cellWidth to height - (sRow - topRow + 1) * cellHeight
        else (eCol + 1) * cellWidth to height - (eRow - topRow + 1) * cellHeight

    private fun hitHandle(x: Float, y: Float): Int {
        val reach = Scl.scl(26f)
        val (ax, ay) = handleAnchor(true)
        if (Mathf.dst(x, y, ax - hr, ay - hr) < reach) return 1
        val (bx, by) = handleAnchor(false)
        if (Mathf.dst(x, y, bx + hr, by - hr) < reach) return 2
        return 0
    }

    private fun dragHandleTo(x: Float, y: Float) {
        val emu = session?.emulator ?: return
        // 手指按在拖柄中心，拖柄在文字下方：往上偏半行 + 一个拖柄半径找对应的格子
        val ly = y + hr + cellHeight * 0.5f
        val (_, row) = cellClamped(x, ly)
        if (dragHandle == 1) {
            val col = Mathf.round((x + hr) / cellWidth).coerceIn(0, emu.mColumns - 1)
            if (row > eRow || row == eRow && col > eCol) { setSelection(eCol, eRow, col, row); dragHandle = 2 } else { sCol = col; sRow = row; onSelectionChanged() }
        } else {
            val col = (Mathf.round((x - hr) / cellWidth) - 1).coerceIn(0, emu.mColumns - 1)
            if (row < sRow || row == sRow && col < sCol) { setSelection(col, row, sCol, sRow); dragHandle = 1 } else { eCol = col; eRow = row; onSelectionChanged() }
        }
        // 拖到上下边缘时跟着翻页
        if (y > height - cellHeight) scrollBy(-1) else if (y < cellHeight) scrollBy(1)
    }

    private fun drawSelection(top: Float) {
        Draw.color(selColor)
        for (r in 0 until rows) {
            val ext = r + topRow
            if (ext < sRow || ext > eRow) continue
            val c0 = if (ext == sRow) sCol else 0
            val c1 = if (ext == eRow) eCol + 1 else columns
            if (c1 <= c0) continue
            Fill.crect(x + c0 * cellWidth, top - (r + 1) * cellHeight, (c1 - c0) * cellWidth, cellHeight)
        }
        Draw.reset()
        val r = hr
        if (sRow - topRow in 0 until rows) {
            val (ax, ay) = handleAnchor(true)
            Shapes.rect(x + ax - 2 * r, y + ay - 2 * r, 2 * r, 2 * r, r, 0f, r, r, handleColor, 0f, null)
        }
        if (eRow - topRow in 0 until rows) {
            val (bx, by) = handleAnchor(false)
            Shapes.rect(x + bx, y + by - 2 * r, 2 * r, 2 * r, 0f, r, r, r, handleColor, 0f, null)
        }
    }

    /** [delta] > 0 向下（更新的内容），< 0 向上翻历史。 */
    fun scrollBy(delta: Int) {
        val s = session ?: return
        val maxBack = s.emulator.screen.activeTranscriptRows
        topRow = (topRow + delta).coerceIn(-maxBack, 0)
    }

    fun scrollToBottom() { topRow = 0 }

    /** 当前查看范围或全部仍在回滚缓冲中的纯文本，保留中文与原有缩进。仅在 UI 状态线程读取。 */
    fun copyText(all: Boolean = false): String {
        val s = session ?: return ""
        s.pump()
        val emu = s.emulator
        val screen = emu.screen
        topRow = topRow.coerceIn(-screen.activeTranscriptRows, 0)
        val first = if (all) -screen.activeTranscriptRows else topRow
        val last = if (all) emu.mRows - 1 else (topRow + rows - 1).coerceAtMost(emu.mRows - 1)
        if (last < first) return ""
        return screen.getSelectedText(0, first, emu.mColumns - 1, last)
    }

    override fun sizeChanged() {
        super.sizeChanged()
        columns = (width / cellWidth).toInt().coerceAtLeast(4)
        rows = (height / cellHeight).toInt().coerceAtLeast(2)
        session?.resize(columns, rows)
    }

    override fun draw() {
        val s = session
        validate()
        Draw.color(background) // 终端在两套主题下都是深色
        Fill.crect(x, y, width, height)
        if (s == null) return
        if (s.pump() && topRow != 0) topRow = topRow.coerceAtLeast(-s.emulator.screen.activeTranscriptRows)
        if (s.emulator.mColumns != columns || s.emulator.mRows != rows) s.resize(columns, rows)
        val emu = s.emulator
        val screen = emu.screen
        val palette = emu.mColors.mCurrentColors
        val reverse = emu.isReverseVideo
        val top = y + height
        val cursorVisible = topRow == 0 && emu.shouldCursorBeVisible()
        for (r in 0 until rows) {
            val ext = r + topRow
            val row = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(ext))
            val text = row.mText
            val used = row.spaceUsed
            val rowTop = top - r * cellHeight
            var col = 0
            var i = 0
            while (col < emu.mColumns) {
                var cp = ' '.code
                var charCount = 0
                if (i < used) {
                    cp = Character.codePointAt(text, i)
                    charCount = Character.charCount(cp)
                }
                val w = if (i < used) WcWidth.width(cp) else 1
                if (w == 0 && i < used) { i += charCount; continue } // 组合字符：简化处理，跳过
                val cells = w.coerceAtLeast(1)
                val style = row.getStyle(col)
                val effect = TextStyle.decodeEffect(style)
                var fore = color(palette, TextStyle.decodeForeColor(style))
                var back = color(palette, TextStyle.decodeBackColor(style))
                val foreIndex = TextStyle.decodeForeColor(style)
                if (effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0 && foreIndex in 0..7) fore = palette[foreIndex + 8]
                var inverse = (effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0) xor reverse
                if (cursorVisible && r == emu.cursorRow && col == emu.cursorCol) inverse = !inverse
                if (inverse) { val t = fore; fore = back; back = t }
                val cx = x + col * cellWidth
                if (inverse || TextStyle.decodeBackColor(style) != TextStyle.COLOR_INDEX_BACKGROUND) {
                    bg.argb8888(back)
                    Draw.color(bg)
                    Fill.crect(cx, rowTop - cellHeight, cellWidth * cells, cellHeight)
                }
                if (cp != ' '.code && effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE == 0) {
                    fg.argb8888(fore)
                    if (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0) fg.mul(0.66f, 0.66f, 0.66f, 1f)
                    sb.setLength(0); sb.appendCodePoint(cp)
                    val f = if (cp < 0x80 || monoHas(cp)) mono else fallback
                    f.color = fg
                    f.draw(sb, cx, rowTop - (cellHeight - f.capHeight) / 2f + f.descent * 0.2f, 0, sb.length, cellWidth * cells, Align.center, false)
                    if (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0) {
                        Draw.color(fg)
                        Fill.crect(cx, rowTop - cellHeight + 1f, cellWidth * cells, 1.5f)
                    }
                }
                col += cells
                i += if (i < used) charCount else 0
            }
        }
        if (selActive) drawSelection(top)
        Draw.reset()
        mono.color = Color.white
        fallback.color = Color.white
    }

    private fun monoHas(cp: Int): Boolean {
        if (cp > 0xFFFF || mono === fallback) return false
        val g = mono.data.getGlyph(cp.toChar())
        return g != null && g !== mono.data.missingGlyph
    }

    private fun color(palette: IntArray, index: Int): Int =
        if (index and 0xff000000.toInt() == 0xff000000.toInt()) index else palette[index]

    /** 把控件内坐标换算成终端列行（预留给选择复制）。 */
    fun cellAt(lx: Float, ly: Float): Pair<Int, Int> = (lx / cellWidth).toInt() to ((height - ly) / cellHeight).toInt() + topRow

    companion object {
        fun requestRender() = Core.graphics.requestRendering()

        val TERMINAL_BG: Color = Color.valueOf("15171a")
    }
}
