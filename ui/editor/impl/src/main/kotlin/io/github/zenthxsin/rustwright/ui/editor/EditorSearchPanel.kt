package io.github.zenthxsin.rustwright.ui.editor

import arc.Core
import arc.scene.ui.TextField
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import io.github.zenthxsin.rustwright.ui.kit.SheetPage
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.listRow
import io.github.zenthxsin.rustwright.ui.widgets.code.CodeEditor
import io.github.zenthxsin.rustwright.ui.widgets.code.Pos
import io.github.zenthxsin.rustwright.core.editor.search.EditorRegexTemplate
import io.github.zenthxsin.rustwright.core.editor.search.EditorRegexTemplates
import io.github.zenthxsin.rustwright.core.editor.search.SearchOptions
import io.github.zenthxsin.rustwright.core.editor.search.TextSearch
import java.util.regex.PatternSyntaxException

/**
 * 查找替换（原编辑器 EditorSearchPanel）：
 * - 第一行：展开 / 收起替换、查找框、选项（忽略大小写 / 正则 / 全词 + 正则模板）、关闭
 * - 第二行（展开时）：替换框（正则时可用 `$1`）
 * - 第三行：上一个、下一个、替换、全部替换，右侧「正则」标记和「当前/总数」
 * 匹配项在编辑区里画底色，当前项更深；正则写错时查找框标红、计数归零。
 */
class EditorSearchPanel(
    private val area: () -> CodeEditor?,
    private val project: () -> ProjectContext?,
    private val templates: () -> List<EditorRegexTemplate>,
    private val onClose: () -> Unit,
) : Table() {
    private var options = SearchOptions()
    private var replaceShown = false
    private var matches: List<IntRange> = emptyList()
    private var current = -1
    private var error = false
    private var searchedVersion = -1
    private var searchedEditor: CodeEditor? = null
    private var searchedQuery: String? = null
    private var searchedOptions: SearchOptions? = null

    private val query = field("查找")
    private val replacement = field("替换为")
    private val counter = K.mono("0/0", 12f, K.subtle, 14f)
    private val regexMark = K.text("正则", 12f, K.blue, lh = 12f)
    private val replaceRow = Table()
    private val actions = Table()
    private val toggle = Tap(null, 8f) { replaceShown = !replaceShown; rebuild() }
    private val queryBox = Table()

    init {
        background(K.fill(K.surface, 12f))
        margin(dp(6f), dp(6f), dp(6f), dp(6f))
        toggle.name = "展开替换"
        val row1 = Table()
        row1.add(toggle).size(dp(32f))
        queryBox.add(query).growX().height(dp(36f))
        row1.add(queryBox).growX().padLeft(dp(2f))
        row1.add(Tap(null, 8f) { showOptions() }.apply { name = "查找选项"; add(K.svg(PI.sliders, 16f, K.muted, 1.8f)) }).size(dp(34f)).padLeft(dp(4f))
        row1.add(Tap(null, 8f) { close() }.apply { name = "关闭查找"; add(K.svg(PI.close, 16f, K.muted, 2f)) }).size(dp(34f))
        add(row1).growX().row()
        add(replaceRow).growX().row()
        add(actions).growX().padTop(dp(4f))
        query.changed { refresh(force = true) }
        rebuild()
    }

    private fun field(hint: String) = TextField("", TextField.TextFieldStyle().apply {
        font = RwTheme.monoAt(14f, 18f)
        fontColor = K.text
        messageFont = RwTheme.text(14f, 400)
        messageFontColor = K.subtle
        cursor = RwTheme.solid(K.text).also { it.minWidth = dp(1.5f) }
        selection = RwTheme.solid(K.blue.cpy().a(0.3f))
        background = K.fill(K.input, 8f).also { it.leftWidth = dp(10f); it.rightWidth = dp(10f) }
    }).apply { setMessageText(hint); name = hint }

    /** 打开时把编辑区选中的文字带进查找框。 */
    fun focus() {
        val sel = area()?.selectedText?.takeIf { it.isNotEmpty() && '\n' !in it }
        if (sel != null) query.text = sel
        Core.scene?.setKeyboardFocus(query)
        query.selectAll()
        refresh(force = true)
    }

    private fun close() {
        area()?.let { it.matches = emptyList(); it.currentMatch = -1; it.focusAndShowKeyboard() }
        onClose()
    }

    override fun act(delta: Float) {
        super.act(delta)
        if (visible) refresh()
    }

    private fun rebuild() {
        toggle.clearChildren()
        toggle.add(K.svg(if (replaceShown) PI.chevronDown else PI.chevronRight, 16f, K.muted, 2f))
        replaceRow.clearChildren()
        if (replaceShown) {
            replacement.setMessageText(if (options.regex) "替换为（可用 \$1）" else "替换为")
            replaceRow.add(replacement).growX().height(dp(36f)).padLeft(dp(34f)).padRight(dp(72f)).padTop(dp(4f))
        }
        actions.clearChildren()
        fun action(label: String, onClick: () -> Unit) = Tap(null, 8f, onClick).apply {
            name = label
            margin(0f, dp(10f), 0f, dp(10f))
            add(K.text(label, 13f, K.text, lh = 13f))
        }
        actions.add(action("上一个") { step(-1) }).height(dp(32f))
        actions.add(action("下一个") { step(1) }).height(dp(32f))
        if (replaceShown) {
            actions.add(action("替换") { replaceCurrent() }).height(dp(32f))
            actions.add(action("全部替换") { replaceAll() }).height(dp(32f))
        }
        actions.add().growX()
        actions.add(regexMark).padRight(dp(8f))
        actions.add(counter).padRight(dp(6f))
        regexMark.visible = options.regex
    }

    /** 文字、查找串或选项变了就重新找；当前项取光标处或之后第一个。 */
    private fun refresh(force: Boolean = false) {
        val a = area() ?: return
        val q = query.text
        if (!force && a === searchedEditor && a.doc.version == searchedVersion && q == searchedQuery && options == searchedOptions) return
        searchedEditor = a; searchedVersion = a.doc.version; searchedQuery = q; searchedOptions = options
        error = false
        val text = a.doc.text
        matches = try { TextSearch.findAll(text, q, options) } catch (_: PatternSyntaxException) { error = true; emptyList() }
        val cursor = a.doc.offsetOf(a.cursor)
        current = if (matches.isEmpty()) -1 else matches.indexOfFirst { it.first >= cursor }.let { if (it < 0) 0 else it }
        show(a, reveal = false)
        query.style.fontColor = if (error) EditorColors.ERROR else K.text
    }

    private fun show(a: CodeEditor, reveal: Boolean) {
        a.matches = matches.map { a.doc.posOf(it.first) to a.doc.posOf(it.last + 1) }
        a.currentMatch = current
        counter.setText("${if (current >= 0) current + 1 else 0}/${matches.size}")
        if (reveal && current in matches.indices) {
            val m = matches[current]
            a.select(a.doc.posOf(m.first), a.doc.posOf(m.last + 1))
            a.revealCursor()
        }
    }

    private fun step(d: Int) {
        val a = area() ?: return
        refresh()
        if (matches.isEmpty()) return
        current = ((if (current < 0) 0 else current + d) % matches.size + matches.size) % matches.size
        show(a, reveal = true)
    }

    private fun replaceCurrent() {
        if (area()?.editable != true) return
        val a = area() ?: return
        refresh()
        val m = matches.getOrNull(current) ?: return
        val rep = runCatching { TextSearch.expand(a.doc.text, query.text, options, m, replacement.text) }.getOrNull() ?: return
        val end = a.replace(a.doc.posOf(m.first), a.doc.posOf(m.last + 1), rep)
        a.setCursor(end)
        refresh(force = true)
        if (matches.isNotEmpty()) show(a, reveal = true)
    }

    private fun replaceAll() {
        if (area()?.editable != true) return
        val a = area() ?: return
        val (out, n) = runCatching { TextSearch.replaceAll(a.doc.text, query.text, options, replacement.text) }.getOrNull() ?: return
        if (n == 0) return
        val c = a.cursor
        // 一次编辑替换全文：撤销一步就能回来
        a.replace(Pos(0, 0), a.doc.end(), out)
        a.setCursor(c)
        refresh(force = true)
        project()?.notify("已替换 $n 处")
    }

    private fun showOptions() {
        val ctx = project() ?: return
        val g = ListGroup()
        fun toggleRow(label: String, on: Boolean, flip: () -> Unit) {
            g.row(listRow(label, null, if (on) PI.check else null, K.accent, minHeight = 52f) { flip(); ctx.closeSheet(); rebuild(); refresh(force = true) })
        }
        toggleRow("忽略大小写", options.ignoreCase) { options = options.copy(ignoreCase = !options.ignoreCase) }
        toggleRow("正则表达式", options.regex) { options = options.copy(regex = !options.regex, wholeWord = if (!options.regex) false else options.wholeWord) }
        toggleRow("全词匹配", options.wholeWord) { options = options.copy(wholeWord = !options.wholeWord, regex = if (!options.wholeWord) false else options.regex) }
        val body = Table().top()
        body.add(g).growX().row()
        body.add(K.text("正则模板", 13f, K.subtle, lh = 13f)).left().pad(dp(16f), dp(20f), dp(6f), dp(20f)).row()
        val tg = ListGroup()
        (EditorRegexTemplates + templates()).distinctBy { it.label }.forEach { t -> tg.row(listRow(t.label, t.hint, PI.codeBrackets, K.muted, minHeight = 60f) { ctx.closeSheet(); apply(t) }) }
        body.add(tg).growX()
        ctx.sheet(SheetPage("查找选项", arc.scene.ui.ScrollPane(body).apply { setScrollingDisabledX(true) }, height = 640f))
    }

    /** 选模板：写入查找（及替换），开正则、关全词。 */
    private fun apply(t: EditorRegexTemplate) {
        query.text = t.find
        if (t.replace != null) { replacement.text = t.replace; replaceShown = true }
        options = options.copy(regex = true, wholeWord = false)
        rebuild()
        refresh(force = true)
    }
}
