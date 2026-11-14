package io.github.zenthxsin.rustwright.ui.editor

import arc.math.Interp
import arc.scene.event.InputEvent
import arc.scene.event.InputListener
import arc.scene.event.Touchable
import arc.scene.ui.layout.Table
import arc.scene.ui.layout.WidgetGroup
import arc.util.Time
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.widgets.code.CodeEditor
import io.github.zenthxsin.rustwright.ui.widgets.code.Pos

/**
 * 键盘上方的符号抽屉：平时一排 7 个写 INI 常用的符号；按住往上拉，抽屉高度跟手，松手过半弹到全开，否则缩回（缓动动画）。
 * 全开后分「常用 / 取值 / 段落」三页；段落页插到下一行，带编号的段落自动接着编号。
 * 性能：两层内容各建一次，拉动时只改高度、换显示层，不重建控件。
 */
class EditorSymbolDrawer(private val support: () -> io.github.zenthxsin.rustwright.ui.editor.api.EditorSupport, private val editor: () -> CodeEditor?) : WidgetGroup() {
    private val rowH = dp(44f)
    private val tabsH = dp(37f)
    private var page = 0
    private var height0 = rowH
    private var target = rowH
    private var from = rowH
    private var animT = 1f
    private var dragging = false
    private val row = Table()
    private val panel = Table().top()
    private val grid = Table().top()
    private val tabBar = Table()

    var open = false
        private set

    init {
        touchable = Touchable.enabled
        for ((label, text) in ROW) row.add(key(label) { insert(text) }).growX().height(rowH)
        tabBar.defaults().height(tabsH)
        panel.add(tabBar).growX().row()
        panel.add(Table(K.fill(K.line, 0f))).growX().height(dp(1f)).row()
        panel.add(grid).growX().top()
        addChild(row); addChild(panel)
        buildPage()
        addListener(object : InputListener() {
            var y0 = 0f; var h0 = 0f; var moved = false
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: arc.input.KeyCode): Boolean {
                if (pointer != 0) return false
                y0 = event.stageY; h0 = height0; moved = false; return true
            }
            override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                val dy = event.stageY - y0
                if (!moved && kotlin.math.abs(dy) < dp(6f)) return
                if (!moved) { moved = true; dragging = true; arc.Core.scene?.cancelTouchFocusExcept(this, this@EditorSymbolDrawer) }
                setHeight0((h0 + dy).coerceIn(rowH, full() + dp(24f)))
            }
            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: arc.input.KeyCode) {
                if (!moved) return
                dragging = false
                animateTo(height0 > (rowH + full()) / 2)
            }
        })
    }

    private fun full() = tabsH + dp(1f) + gridHeight()
    private fun gridHeight() = grid.prefHeight + dp(8f)

    /** 直接打开到某一页（侧栏「新段落」用）。 */
    fun showPage(i: Int) { if (page != i) { page = i; buildPage() }; animateTo(true) }

    /** 打开 / 收起（带缓动）。 */
    fun animateTo(open: Boolean) {
        this.open = open
        from = height0; target = if (open) full() else rowH; animT = 0f
    }

    private fun setHeight0(h: Float) {
        if (h == height0) return
        height0 = h
        invalidateHierarchy()
    }

    override fun act(delta: Float) {
        super.act(delta)
        if (!dragging && animT < 1f) {
            animT = (animT + Time.delta / 60f / 0.24f).coerceAtMost(1f)
            setHeight0(from + (target - from) * Interp.pow3Out.apply(animT))
        }
    }

    override fun getPrefHeight() = height0
    override fun getPrefWidth() = 0f

    override fun layout() {
        val showPanel = height0 > rowH + dp(30f)
        row.visible = !showPanel; panel.visible = showPanel
        row.setBounds(0f, height - rowH, width, rowH)
        val ph = full()
        panel.setBounds(0f, height - ph, width, ph)
        row.validate(); panel.validate()
    }

    override fun drawChildren() {
        // 裁掉超出当前高度的部分（拉到一半时只露出上面一截）
        if (clipBegin()) { super.drawChildren(); clipEnd() }
    }

    override fun draw() {
        val a = color.a * parentAlpha
        arc.graphics.g2d.Shapes.rect(x, y, width, height, if (height0 > rowH + dp(4f)) dp(14f) else 0f, arc.graphics.Color(K.surface).a(a), 0f, arc.graphics.Color.clear)
        arc.graphics.g2d.Shapes.rect(x, y + height - dp(1f), width, dp(1f), 0f, arc.graphics.Color(K.line).a(a), 0f, arc.graphics.Color.clear)
        super.draw()
    }

    private fun key(label: String, action: () -> Unit) = Tap(null, 6f) { action() }.apply {
        name = "符号 $label"
        add(K.mono(label, if (label.length > 2) 12.5f else 16f, K.dl("9DB0CC", "4E6585"), 18f).apply { setEllipsis(true) }).minWidth(0f)
    }

    private fun buildPage() {
        tabBar.clearChildren()
        listOf("常用", "取值", "段落").forEachIndexed { i, n ->
            val t = Tap(null, 0f) { if (page != i) { page = i; buildPage(); if (open) animateTo(true) } }
            t.name = "符号页 $n"
            t.margin(0f, dp(14f), 0f, dp(14f))
            t.add(K.text(n, 13.5f, if (page == i) K.blue else K.subtle, lh = 13.5f))
            tabBar.add(t)
        }
        grid.clearChildren()
        if (page == 2) {
            grid.defaults().growX().height(dp(46f)).pad(dp(3f))
            support().sections().forEachIndexed { i, (name, cn) ->
                val cell = Tap(K.surface2, 8f) { insertSection(name) }
                cell.name = "段落 $name"
                cell.add(K.mono("[$name${if (name.endsWith("_")) "N" else ""}]", 12.5f, K.dl("E0A270", "B0662A"), 14f).apply { setEllipsis(true) }).minWidth(0f).row()
                cell.add(K.text(cn, 11f, K.subtle, lh = 11f)).padTop(dp(2f))
                grid.add(cell).uniformX()
                if (i % 3 == 2) grid.row()
            }
            grid.margin(dp(5f), dp(7f), dp(5f), dp(7f))
        } else {
            val items = if (page == 0) COMMON else support().values()
            val cols = if (page == 0) 7 else 4
            grid.defaults().growX().height(rowH)
            items.forEachIndexed { i, (label, text) ->
                grid.add(key(label) { insert(text) }).uniformX()
                if (i % cols == cols - 1) grid.row()
            }
            grid.margin(dp(2f), dp(4f), dp(2f), dp(4f))
        }
        grid.invalidateHierarchy()
    }

    private fun insert(text: String) {
        val e = editor() ?: return
        if (text == "[]" || text == "{}") e.insert(text, 1) else e.insert(text)
        e.focusAndShowKeyboard()
    }

    private fun insertSection(name: String) {
        val e = editor() ?: return
        val doc = e.doc
        val text = if (name.endsWith("_")) {
            val n = (0 until doc.lineCount).count { doc.line(it).trim().startsWith("[$name") } + 1
            "[$name$n]"
        } else "[$name]"
        val (line, insert, cursorLine) = sectionInsertion(doc.linesSnapshot(), e.cursor.line, text)
        val at = Pos(line, doc.line(line).length)
        e.replace(at, at, insert)
        e.setCursor(Pos(cursorLine, 0))
        e.focusAndShowKeyboard()
    }

    companion object {
        /**
         * 新段落插在哪：当前段落最后一个非空行之后（不把段落从中间劈开），前面空一行，光标停在段落头下面的新行。
         * 返回（接在哪一行末尾，要插入的文字，插完光标所在行）。空文件直接写在第一行。
         */
        fun sectionInsertion(lines: List<String>, cursorLine: Int, header: String): Triple<Int, String, Int> {
            if (lines.all { it.isBlank() }) return Triple(0, "$header\n", 1)
            var end = cursorLine.coerceIn(0, lines.size - 1)
            while (end + 1 < lines.size && !lines[end + 1].trimStart().startsWith("[")) end++
            while (end > 0 && lines[end].isBlank()) end--
            return Triple(end, "\n\n$header\n", end + 3)
        }

        private val ROW = listOf(":" to ": ", "[" to "[", "]" to "]", "_" to "_", "#" to "# ", "." to ".", "," to ", ")
        private val COMMON = ROW + listOf("-" to "-", "/" to "/", "=" to "=", "\"" to "\"", "%" to "%", "*" to "*", "+" to "+", "(" to "(", ")" to ")", "<" to "<", ">" to ">", "[ ]" to "[]", "{ }" to "{}", "…" to "...")
    }
}
