package io.github.zenthxsin.rustwright.ui.editor

import arc.Core
import arc.graphics.Color
import arc.math.Interp
import arc.scene.Element
import arc.scene.actions.Actions
import arc.scene.event.Touchable
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import arc.scene.ui.layout.WidgetGroup
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import io.github.zenthxsin.rustwright.ui.kit.SheetPage
import io.github.zenthxsin.rustwright.ui.kit.listRow
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.widgets.code.CodeEditor
import io.github.zenthxsin.rustwright.ui.widgets.code.Pos
import java.io.File

/**
 * 两个编辑器（INI 编辑器、普通文本编辑器）共用的外壳，沿用 App 自己的样子：
 * - 一行融合顶栏：返回 · 文件名（点开换文件，下面一行 行:列 和问题数）· 撤销 重做 保存 · 编辑 · 更多
 * - 「编辑」「更多」是和对话菜单同款的浮层菜单；换文件走底部弹层
 * - 行操作（复制行、剪切行、删除行、重复行、大小写、缩进、注释）和选区图标菜单
 */
class EditorChrome(private val editor: CodeEditor) {
    val doc get() = editor.doc

    init {
        // 编辑区跟 App 底色走，只有语法、错误、标识符高亮用 IDEA 的颜色
        editor.background.set(K.bg)
        editor.gutterBackground.set(K.bg)
        editor.currentLineColor.set(K.surface2)
        editor.lineNumberColor.set(K.subtle)
        editor.currentLineNumberColor.set(K.text)
        editor.dividerColor.set(K.line)
        editor.handleColor.set(K.blue)
    }

    // ---------------- 顶栏 ----------------

    fun icon(path: String, label: String, color: Color = K.text, onClick: () -> Unit) = Tap(null, 22f, onClick).apply {
        name = label
        add(K.svg(path, 20f, color, 1.8f))
    }

    /**
     * 顶栏：上面一行 返回 ……… [icons]；下面细细一行 [title]（点开 [files] 换文件）……… [status]（问题数、行:列）。
     */
    fun toolbar(back: () -> Unit, files: () -> Unit, icons: List<Element>, title: Element, status: Element): Table {
        // 对齐：返回箭头左边离屏幕边 16dp，和下面一行的文件名对齐
        val bar = Table()
        bar.margin(0f, dp(5f), 0f, dp(6f))
        bar.add(icon(PI.back, "返回") { back() }.apply { clearChildren(); add(K.svg(PI.back, 22f, K.text, 1.8f)) }).size(dp(44f))
        bar.add().growX()
        for (e in icons) bar.add(e).size(dp(40f))
        val info = Table()
        // 右边对齐到「更多」三个点的竖线上（三个点很窄，看起来的右边就是图标中线）
        info.margin(0f, dp(16f), 0f, dp(25f))
        val t = Tap(null, 6f) { files() }
        t.name = "换文件"
        t.add(title).minWidth(0f).left()
        t.add(K.svg(PI.chevronDown, 11f, K.subtle, 2f)).padLeft(dp(4f))
        info.add(t).minWidth(0f).left()
        info.add().growX()
        info.add(status).right().padLeft(dp(12f))
        val box = Table()
        box.add(bar).growX().height(dp(52f)).row()
        box.add(info).growX().height(dp(26f)).row()
        box.add(Table(K.fill(K.line, 0f))).growX().height(dp(1f)).row()
        return box
    }

    // ---------------- 浮层菜单 ----------------

    /** 一项菜单：[checked] 非空时是开关，开着时右边打勾；[enabled] 为 false 时灰掉。 */
    class Entry(val label: String, val icon: String, val checked: Boolean? = null, val enabled: Boolean = true, val action: () -> Unit)

    /** 贴着 [anchor] 下方弹出（见 [menu]）。 */
    fun popup(anchor: Element, entries: List<Entry>) = menu(anchor, entries)

    // ---------------- 换文件 ----------------

    /** 换文件：底部弹层列出项目里的文本文件，当前这个打勾。 */
    fun filesSheet(ctx: ProjectContext, files: List<File>, current: File?, root: File?, onPick: (File) -> Unit) {
        if (files.isEmpty()) return ctx.notify(if (root == null) "先选一个项目" else "项目里还没有文本文件")
        val g = ListGroup()
        for (f in files.take(120)) {
            val rel = root?.let { f.relativeToOrNull(it)?.parent }?.ifEmpty { null } ?: "项目根目录"
            g.row(listRow(f.name, rel, PI.code, K.muted, trailing = if (f == current) "当前" else null, minHeight = 60f) {
                ctx.closeSheet()
                if (f != current) onPick(f)
            })
        }
        ctx.sheet(SheetPage("换文件", Table().top().apply { add(g).growX() }, height = 540f))
    }

    // ---------------- 行操作 ----------------

    private val lines: IntRange get() = editor.selectionStart.line..editor.selectionEnd.line.let { e ->
        // 选区停在下一行行首时不算那一行
        if (editor.hasSelection && editor.selectionEnd.col == 0 && e > editor.selectionStart.line) e - 1 else e
    }

    private fun linesText(r: IntRange) = r.joinToString("\n") { doc.line(it) }

    /** 把 [r] 这几行整体换成 [text]，光标放到 [cursor]（默认原位）。 */
    private fun replaceLines(r: IntRange, text: String, cursor: Pos? = null) {
        val from = Pos(r.first, 0)
        val to = Pos(r.last, doc.line(r.last).length)
        val keep = editor.cursor
        editor.replace(from, to, text)
        editor.setCursor(cursor ?: Pos(keep.line.coerceAtMost(doc.lineCount - 1), keep.col.coerceAtMost(doc.line(keep.line.coerceAtMost(doc.lineCount - 1)).length)))
        editor.ensureCursorVisible()
    }

    fun copyLine() { Core.app.clipboardText = linesText(lines) + "\n" }

    fun cutLine() { copyLine(); deleteLine() }

    fun deleteLine() {
        val r = lines
        if (r.last < doc.lineCount - 1) { editor.replace(Pos(r.first, 0), Pos(r.last + 1, 0), ""); editor.setCursor(Pos(r.first, 0)) }
        else if (r.first > 0) { editor.replace(Pos(r.first - 1, doc.line(r.first - 1).length), Pos(r.last, doc.line(r.last).length), ""); editor.setCursor(Pos(r.first - 1, 0)) }
        else { editor.replace(Pos(0, 0), Pos(r.last, doc.line(r.last).length), ""); editor.setCursor(Pos(0, 0)) }
        editor.ensureCursorVisible()
    }

    fun clearLine() { val r = lines; replaceLines(r, r.joinToString("\n") { "" }, Pos(r.first, 0)) }

    fun duplicateLine() {
        val r = lines
        val t = linesText(r)
        editor.replace(Pos(r.last, doc.line(r.last).length), Pos(r.last, doc.line(r.last).length), "\n" + t)
        editor.setCursor(Pos(editor.cursor.line + r.count(), editor.cursor.col))
        editor.ensureCursorVisible()
    }

    fun replaceLineWithClipboard() { val t = Core.app.clipboardText ?: return; replaceLines(lines, t.trimEnd('\n')) }

    fun upper() = caseChange { it.uppercase() }
    fun lower() = caseChange { it.lowercase() }

    private fun caseChange(f: (String) -> String) {
        if (editor.hasSelection) {
            val s = editor.selectionStart; val e = editor.selectionEnd
            val t = f(editor.selectedText)
            editor.replace(s, e, t)
            editor.select(s, e)
        } else { val r = lines; replaceLines(r, f(linesText(r))) }
    }

    fun indent() { val r = lines; replaceLines(r, r.joinToString("\n") { "    " + doc.line(it) }, Pos(editor.cursor.line, editor.cursor.col + 4)) }

    fun outdent() {
        val r = lines
        val cut = doc.line(editor.cursor.line).takeWhile { it == ' ' }.length.coerceAtMost(4)
        replaceLines(r, r.joinToString("\n") { l -> val t = doc.line(l); t.removePrefix(" ".repeat(t.takeWhile { it == ' ' }.length.coerceAtMost(4))).removePrefix("\t") }, Pos(editor.cursor.line, (editor.cursor.col - cut).coerceAtLeast(0)))
    }

    /** 切换注释（前缀由调用方指定）：全是注释就去掉，否则全部加上。 */
    fun toggleComment(prefix: String = "# ") {
        val r = lines
        val all = r.all { doc.line(it).trimStart().startsWith(prefix.trim()) || doc.line(it).isBlank() }
        replaceLines(r, r.joinToString("\n") { l ->
            val t = doc.line(l)
            val ind = t.takeWhile { it == ' ' || it == '\t' }
            if (all) ind + t.substring(ind.length).removePrefix(prefix.trim()).removePrefix(" ") else if (t.isBlank()) t else ind + prefix + t.substring(ind.length)
        })
    }

    /** 「编辑」菜单：行操作。 */
    fun editEntries(extra: List<Entry> = emptyList()): List<Entry> = listOf(
        Entry("复制行", PI.copy) { copyLine() },
        Entry("剪切行", PI.scissors, enabled = editor.editable) { cutLine() },
        Entry("删除行", PI.trash, enabled = editor.editable) { deleteLine() },
        Entry("清空行", PI.eraser, enabled = editor.editable) { clearLine() },
        Entry("替换行", PI.paste, enabled = editor.editable) { replaceLineWithClipboard() },
        Entry("重复行", PI.copyPlus, enabled = editor.editable) { duplicateLine() },
        Entry("转为大写", PI.caseUpper, enabled = editor.editable) { upper() },
        Entry("转为小写", PI.caseLower, enabled = editor.editable) { lower() },
        Entry("增加缩进", PI.indentMore, enabled = editor.editable) { indent() },
        Entry("减小缩进", PI.indentLess, enabled = editor.editable) { outdent() },
        Entry("切换注释", PI.hash, enabled = editor.editable) { toggleComment() },
    ) + extra

    /** 选区菜单：图标一排（查找 · 全选 · 剪切 · 复制 · 粘贴 · 更多）。 */
    fun selectionMenu(search: (() -> Unit)?, more: (Element) -> Unit) {
        val items = ArrayList<CodeEditor.MenuItem>()
        fun ic(p: String) = K.svg(p, 20f, K.text, 1.8f)
        if (search != null) items += CodeEditor.MenuItem("查找", ic(PI.search)) { search() }
        items += CodeEditor.MenuItem("全选", ic(PI.selectAll), keepOpen = true) { editor.selectAll() }
        items += CodeEditor.MenuItem("剪切", ic(PI.scissors)) { editor.cut() }
        items += CodeEditor.MenuItem("复制", ic(PI.copy)) { editor.copy() }
        items += CodeEditor.MenuItem("粘贴", ic(PI.paste)) { editor.paste() }
        lateinit var moreIcon: Element
        moreIcon = ic(PI.dots)
        items += CodeEditor.MenuItem("更多编辑", moreIcon, keepOpen = true) { more(moreIcon) }
        editor.setMenuItems(items)
    }

    /** 转到指定行（1 起）。 */
    fun gotoLine(line: Int) {
        val l = (line - 1).coerceIn(0, doc.lineCount - 1)
        editor.setCursor(Pos(l, 0))
        editor.revealCursor()
    }

    companion object {
        /** 贴着 [anchor] 下方弹出，和对话右上角菜单同款（圆角 14、浅阴影、淡入上移）。 */
        fun menu(anchor: Element, entries: List<Entry>) {
            val scene = Core.scene ?: return
            val layer = object : WidgetGroup() {}
            layer.setFillParent(true)
            val catcher = Element().apply { setFillParent(true) }
            val close = { layer.actions(Actions.fadeOut(0.1f), Actions.remove()) }
            catcher.clicked { close() }
            layer.addChild(catcher)
            val list = Table().top()
            for (e in entries) {
                val r = Tap(null, 10f) { close(); e.action() }
                r.name = e.label
                r.enabled = e.enabled
                r.margin(0f, dp(14f), 0f, dp(14f))
                val c = if (e.enabled) K.text else K.subtle
                // 和对话右上角菜单（MenuHost）同一套尺寸：行高 50、图标 20、文字 16
                r.add(K.svg(e.icon, 20f, if (e.enabled) Color(c).a(0.8f) else K.subtle, 1.6f)).padRight(dp(16f))
                r.add(K.text(e.label, 16f, c, lh = 16f).apply { setEllipsis(true) }).growX().minWidth(0f).left().padBottom(dp(2f))
                if (e.checked == true) r.add(K.svg(PI.check, 18f, K.blue, 2f)).padLeft(dp(12f))
                list.add(r).growX().height(dp(50f)).row()
            }
            val panel = object : Table() {
                override fun drawBackground(x: Float, y: Float) {
                    val a = color.a * parentAlpha
                    arc.graphics.g2d.Shapes.shadow(x, y, width, height, dp(14f), 0f, -dp(12f), dp(32f), 0f, Color(0f, 0f, 0f, 0.45f * a))
                    arc.graphics.g2d.Shapes.rect(x, y, width, height, dp(14f), Color(K.dl("262624", "F3F2EE")).a(a), dp(1f), Color(K.line).a(a))
                }
            }
            panel.touchable = Touchable.enabled
            panel.margin(dp(6f))
            val pane = ScrollPane(list).apply { setScrollingDisabledX(true); setOverscroll(false, false) }
            panel.add(pane).grow()
            layer.addChild(panel)
            // 只用按钮下方的空间（键盘弹起时可用高度变小）：放不下就在菜单里滚动，菜单顶部不越过按钮、不出屏幕
            fun place(): Float {
                // 键盘高度是 Scene.marginBottom：整个界面根节点被抬高了键盘那么高。菜单层挂在根节点里，
                // 所以必须用「相对根节点」的坐标，不能用屏幕坐标——否则菜单被多抬一个键盘高度，顶出屏幕只剩最后一项
                val root = scene.root
                val rw = root.width; val rh = root.height
                val w = minOf(dp(240f), rw - dp(24f))
                val p = anchor.localToAscendantCoordinates(root, arc.math.geom.Vec2(anchor.width, 0f))
                val top = minOf(p.y - dp(4f), rh - dp(8f))
                val h = minOf(list.prefHeight + dp(12f), top - dp(12f)).coerceAtLeast(minOf(dp(56f), top))
                panel.setBounds((p.x - w).coerceIn(dp(12f), rw - w - dp(12f)), top - h, w, h)
                return top - h
            }
            val y = place()
            // 键盘弹出 / 收起（根节点大小变了）：跟着按钮重新定位
            var lastW = scene.root.width; var lastH = scene.root.height
            layer.update {
                if (scene.root.width != lastW || scene.root.height != lastH) { lastW = scene.root.width; lastH = scene.root.height; Core.app.post { panel.clearActions(); place() } }
            }
            layer.color.a = 0f
            panel.y = y - dp(6f)
            layer.actions(Actions.fadeIn(0.15f, Interp.smooth))
            panel.actions(Actions.moveTo(panel.x, y, 0.15f, Interp.smooth))
            scene.add(layer)
        }

        /** 只读模式、代码补全开关：所有编辑器共用。 */
        @JvmStatic var completion = true
    }
}
