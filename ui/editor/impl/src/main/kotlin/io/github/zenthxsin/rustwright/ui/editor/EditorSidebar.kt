package io.github.zenthxsin.rustwright.ui.editor

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
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.Tap
import java.io.File

/**
 * 编辑器左侧抽屉：「项目」（IDEA 式文件树 + 书签 + 底部统计）和「结构」（段落 / 字段一行一个，右边是值，出错标红）。
 * 从左边滑入、背景淡入；点背景或返回键收起。
 * 性能：只在打开时、切标签时、或打开状态下文档 / 文件变了（外面防抖后调 [refresh]）才重建；关着不做任何事。
 */
class EditorSidebar(private val host: Host) : WidgetGroup() {
    interface Host {
        fun projectDir(): File?
        fun projectName(): String
        fun currentFile(): File?
        fun lines(): List<String>
        fun sectionLabel(section: String): String
        fun outline(): List<io.github.zenthxsin.rustwright.ui.editor.api.OutlineNode>
        fun cursorLine(): Int
        fun errorLines(): Set<Int>
        fun bookmarks(): List<File>
        fun openFile(f: File)
        fun jumpTo(line: Int)
        fun menu(anchor: Element, tab: Int)
        fun removeBookmark(f: File)
        /** 切换项目菜单（项目名那一行右边的「切换」）。 */
        fun projectMenu(anchor: Element)
    }

    private val scrim = Element()
    private val panel = Table().top()
    private val body = Table().top().left()
    private val footer = Table().left()
    private val header = Table()
    private var tab = 0
    private val expanded = HashSet<String>()
    private val folded = HashSet<String>()
    var onlyIssues = false; private set
    var showValues = true; private set
    private var expandedFor: File? = null

    val isOpen get() = visible

    init {
        visible = false
        touchable = Touchable.childrenOnly
        setFillParent(true)
        scrim.touchable = Touchable.enabled
        scrim.clicked { close() }
        panel.touchable = Touchable.enabled
        panel.background = object : arc.scene.style.BaseDrawable() {
            override fun draw(x: Float, y: Float, w: Float, h: Float) {
                arc.graphics.g2d.Shapes.shadow(x, y, w, h, 0f, dp(8f), 0f, dp(32f), 0f, Color(0f, 0f, 0f, 0.4f * panel.color.a))
                arc.graphics.g2d.Shapes.rect(x, y, w, h, 0f, Color(K.dl("1E1E1C", "F5F4F0")), 0f, Color.clear)
                arc.graphics.g2d.Shapes.rect(x + w - dp(1f), y, dp(1f), h, 0f, Color(K.line), 0f, Color.clear)
            }
        }
        panel.add(header).growX().height(dp(44f)).row()
        panel.add(Table(K.fill(K.line, 0f))).growX().height(dp(1f)).row()
        panel.add(ScrollPane(body).apply { setScrollingDisabledX(true); setOverscroll(false, true) }).grow().row()
        panel.add(Table(K.fill(K.line, 0f))).growX().height(dp(1f)).row()
        panel.add(footer).growX().height(dp(34f))
        addChild(scrim); addChild(panel)
    }

    private fun panelWidth() = minOf(dp(320f), width * 0.86f)

    override fun layout() {
        scrim.setBounds(0f, 0f, width, height)
        panel.setSize(panelWidth(), height)
        panel.y = 0f
    }

    override fun draw() {
        // 背景遮罩跟着面板位置一起淡入淡出
        val k = ((panel.x + panelWidth()) / panelWidth()).coerceIn(0f, 1f)
        arc.graphics.g2d.Shapes.rect(x, y, width, height, 0f, Color(0f, 0f, 0f, 0.45f * k * parentAlpha), 0f, Color.clear)
        super.draw()
    }

    fun open(tab: Int = this.tab) {
        this.tab = tab
        visible = true
        toFront()
        rebuildIfChanged()
        layout()
        panel.clearActions()
        if (panel.x >= 0f && panel.x < 1f && visible && panel.actions.isEmpty) panel.x = -panelWidth()
        panel.x = -panelWidth()
        panel.actions(Actions.moveTo(0f, 0f, 0.22f, Interp.pow3Out))
    }

    fun close() {
        if (!visible) return
        panel.clearActions()
        panel.actions(Actions.moveTo(-panelWidth(), 0f, 0.18f, Interp.pow2In), Actions.run { visible = false })
    }

    /** 文档、光标、问题、书签变了：打开着才重建。 */
    fun refresh() { if (visible) rebuildIfChanged() }

    /**
     * 打开侧栏 / 打字后刷新：内容没变（同一页、同一文件、文字、光标行、问题、书签都一样）就不重建，
     * 重建整棵树 / 整份结构在手机上要上百毫秒，正好卡在滑出动画的第一帧。
     */
    private var builtKey = ""
    private fun contentKey(): String {
        val f = host.currentFile()
        return listOf(tab, f?.path, host.projectDir()?.path, host.lines().hashCode(), if (tab == 1) host.cursorLine() else -1,
            host.errorLines().hashCode(), host.bookmarks().map { it.path }.hashCode()).joinToString("|")
    }
    private fun rebuildIfChanged() {
        val k = contentKey()
        if (k == builtKey && body.hasChildren()) return
        rebuild()
    }

    /** 项目统计：同一项目 10 秒内只扫一次目录（侧栏跟着打字刷新，不能每次都走一遍文件树）。 */
    private var statsKey: File? = null
    private var statsAt = 0L
    private var statsVal = 0 to 0
    fun invalidateStats() { statsKey = null }
    private fun stats(root: File): Pair<Int, Int> {
        val now = System.currentTimeMillis()
        if (statsKey == root && now - statsAt < 10_000) return statsVal
        var units = 0; var images = 0
        root.walkTopDown().onEnter { !it.name.startsWith(".") }.maxDepth(5).forEach { if (it.isFile) { if (TextFiles.isText(it)) units++; if (it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp")) images++ } }
        statsKey = root; statsAt = now; statsVal = units to images
        return statsVal
    }

    private fun rebuild() {
        builtKey = contentKey()
        buildHeader()
        body.clearChildren(); footer.clearChildren()
        if (tab == 0) buildProject() else buildStructure()
    }

    // ---------------- 顶部：项目 · 结构 ……… 工具 ≡ ----------------

    private fun buildHeader() {
        header.clearChildren()
        header.margin(0f, dp(6f), 0f, dp(6f))
        listOf("项目", "结构").forEachIndexed { i, n ->
            val on = tab == i
            val t = object : Tap(null, 0f, { if (tab != i) { tab = i; rebuild() } }) {
                override fun draw() { super.draw(); if (on) arc.graphics.g2d.Shapes.rect(x + dp(8f), y, width - dp(16f), dp(2f), dp(1f), Color(K.text).a(parentAlpha), 0f, Color.clear) }
            }
            t.name = "侧栏 $n"
            t.margin(0f, dp(8f), 0f, dp(8f))
            t.add(K.text(n, 13.5f, if (on) K.text else K.subtle, if (on) 500 else 400, 13.5f))
            header.add(t).growY()
        }
        header.add().growX()
        if (tab == 1) {
            header.add(tool(WARN_TRI, "只看有问题", onlyIssues) { onlyIssues = !onlyIssues; rebuild() }).size(dp(32f))
            header.add(tool(PI.eye, "显示值", showValues) { showValues = !showValues; rebuild() }).size(dp(32f))
            header.add(tool(FOLD, "全部收起 / 展开", false) { val all = host.outline().map { it.name }; if (folded.size >= all.size) folded.clear() else folded.addAll(all); rebuild() }).size(dp(32f))
        }
        lateinit var menuBtn: Tap
        menuBtn = tool(PI.menu, "菜单", false) { host.menu(menuBtn, tab) }
        header.add(menuBtn).size(dp(32f))
    }

    private fun tool(icon: String, label: String, on: Boolean, action: () -> Unit) = Tap(if (on) K.surface2 else null, 8f) { action() }.apply {
        name = label
        add(K.svg(icon, 15f, if (on) K.text else K.subtle, 1.8f))
    }

    // ---------------- 项目：书签 + 文件树 ----------------

    private fun buildProject() {
        val root = host.projectDir()
        body.margin(dp(8f), dp(6f), dp(8f), dp(6f))
        if (root == null) { body.add(K.text("未选择项目", 13f, K.subtle)).pad(dp(12f)); return }
        val cur = host.currentFile()
        if (expandedFor != cur && cur != null) {
            expandedFor = cur
            var p = cur.parentFile
            while (p != null && p.startsWith(root)) { expanded += p.path; p = p.parentFile }
        }
        expanded += root.path
        // 书签
        val marks = host.bookmarks().filter { it.isFile }
        val bm = Table().left()
        bm.add(K.svg(STAR, 13f, K.c("E2A84B"), 1.8f)).padRight(dp(6f))
        if (marks.isEmpty()) bm.add(K.text("长按编辑器标签，选「加入书签」", 12f, K.subtle, lh = 12f))
        for (f in marks) {
            val chip = Tap(if (f == cur) K.surface3 else K.surface2, 8f) { host.openFile(f); close() }
            chip.name = "书签 " + f.name
            chip.margin(0f, dp(10f), 0f, dp(10f))
            chip.add(K.text(f.name, 12.5f, if (f == cur) K.text else K.muted, lh = 12.5f))
            bm.add(chip).height(dp(28f)).padRight(dp(6f))
        }
        body.add(ScrollPane(bm).apply { setScrollingDisabledY(true); setOverscroll(true, false) }).growX().height(dp(32f)).padBottom(dp(4f)).row()
        body.add(K.text("按文件夹", 11.5f, K.subtle, lh = 11.5f)).left().pad(dp(6f), dp(8f), dp(2f), 0f).row()
        treeRow(root, 0, cur, true)
        // 底部统计
        val (units, images) = stats(root)
        footer.margin(0f, dp(12f), 0f, dp(12f))
        footer.add(K.text("$units 个文本 · $images 张图片" + host.errorLines().size.let { if (it > 0 && cur != null) " · 当前文件 $it 个错误" else "" }, 11.5f, K.subtle, lh = 11.5f).apply { setEllipsis(true) }).growX().minWidth(0f)
    }

    private fun treeRow(f: File, depth: Int, cur: File?, root: Boolean = false) {
        val isDir = f.isDirectory
        val open = isDir && f.path in expanded
        val on = f == cur
        val gen = false
        val unitDir = isDir && f.listFiles()?.any { it.extension.equals("ini", true) } == true
        val ext = f.extension.lowercase()
        val r = Tap(if (on) K.dl("26303D", "DCE6F4") else null, 6f) {
            if (isDir) { if (open) expanded -= f.path else expanded += f.path; rebuild() } else { host.openFile(f); close() }
        }
        r.name = (if (isDir) "文件夹 " else "文件 ") + f.name
        r.margin(0f, dp(8f + depth * 18f), 0f, dp(8f))
        if (isDir) r.add(K.svg(if (open) PI.chevronDown else PI.chevronRight, 12f, K.subtle, 2.1f)).size(dp(12f)).padRight(dp(4f)) else r.add().size(dp(12f)).padRight(dp(4f))
        val (icon, col) = when {
            isDir -> PI.folder to if (gen) K.c("C98A5A") else K.muted
            ext == "ini" || ext == "template" -> PI.code to K.dl("B898D0", "8A5DB0")
            ext == "png" -> PI.img to K.c("7FB08A")
            ext in setOf("json", "toml", "xml", "yaml", "yml", "js", "py", "kt", "md", "txt") -> PI.code to K.muted
            ext == "ogg" || ext == "wav" -> PI.waves to K.c("E2A84B")
            else -> PI.file to K.subtle
        }
        val iconCell = Table()
        iconCell.add(K.svg(icon, 15f, col, 1.7f))
        if (unitDir) iconCell.addChild(Table(K.fill(K.c("4A7BD0"), 2f)).apply { setBounds(dp(9f), -dp(1f), dp(7f), dp(7f)) })
        r.add(iconCell).size(dp(16f)).padRight(dp(6f))
        val errs = if (on) host.errorLines().size else 0
        r.add(K.text(if (root) host.projectName() else f.name, 13f, when { errs > 0 -> K.dl("E06C5F", "C8443A"); gen -> K.c("C98A5A"); root -> K.text; else -> K.muted }, if (root) 600 else 400, 13f).apply { setEllipsis(true) }).minWidth(0f).left()
        if (root) r.add(K.text("  ~/" + f.name, 11.5f, K.subtle, lh = 11.5f).apply { setEllipsis(true) }).minWidth(0f)
        r.add().growX()
        if (root) {
            lateinit var sw: Tap
            sw = Tap(K.surface2, 6f) { host.projectMenu(sw) }
            sw.name = "切换项目"
            sw.margin(0f, dp(8f), 0f, dp(8f))
            sw.add(K.svg(SWAP, 12f, K.muted, 2f)).padRight(dp(4f))
            sw.add(K.text("切换", 12f, K.muted, lh = 12f))
            r.add(sw).height(dp(24f))
        }
        if (errs > 0) r.add(Table(K.fill(K.dl("5A2A25", "F6D9D4"), 8f)).apply { add(K.text("$errs", 10.5f, EditorColors.ERROR, 600, 10.5f)) }).minSize(dp(16f))
        body.add(r).growX().height(dp(28f)).row()
        if (open) {
            val kids = (f.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            for (k in kids.take(300)) treeRow(k, depth + 1, cur)
        }
    }

    // ---------------- 结构：段落 § / 字段一行一个 ----------------

    private fun buildStructure() {
        val nodes = host.outline(); val errs = host.errorLines(); val cur = host.cursorLine()
        body.margin(dp(8f), dp(6f), dp(8f), dp(6f))
        var shown = 0
        for (node in nodes) {
            val bad = node.line + 1 in errs || node.children.any { it.line + 1 in errs }
            if (onlyIssues && !bad) continue
            shown++
            val head = Table().left()
            val foldedHere = node.name in folded && !onlyIssues
            if (node.children.isNotEmpty()) head.add(Tap(null, 4f) {
                if (!folded.remove(node.name)) folded += node.name; rebuild()
            }.apply { name = "展开或收起 ${node.name}"; add(K.svg(if (foldedHere) PI.chevronRight else PI.chevronDown, 12f, K.subtle, 2f)) }).size(dp(24f))
            val title = Tap(if (node.line == cur) K.surface3 else null, 6f) { host.jumpTo(node.line); close() }.apply {
                name = if (node.name.startsWith("[")) "段落 ${node.name.removeSurrounding("[", "]")}" else "结构 ${node.name}"; margin(dp(6f)); add(K.mono(node.name, 13f, if (bad) EditorColors.ERROR else K.text)).left()
                val label = host.sectionLabel(node.name.removeSurrounding("[", "]"))
                if (label.isNotBlank()) add(K.text(label, 11.5f, K.subtle)).padLeft(dp(6f))
                add().growX(); if (node.children.isNotEmpty()) add(K.text("${node.children.size}", 11f, K.subtle))
            }
            head.add(title).growX(); body.add(head).growX().row()
            if (foldedHere) continue
            for (child in node.children) {
                val error = child.line + 1 in errs
                if (onlyIssues && !error) continue
                val row = Tap(if (child.line == cur) K.surface3 else null, 6f) { host.jumpTo(child.line); close() }.apply {
                    name = "字段 ${child.name}"; margin(dp(6f), dp(30f), dp(6f), dp(8f))
                    add(K.mono(child.name, 13f, if (error) EditorColors.ERROR else K.text)).left().padRight(dp(8f))
                    if (showValues) add(K.mono(child.value, 12.5f, K.subtle).apply { setEllipsis(true) }).growX().minWidth(0f).left()
                    else add().growX()
                    if (error) add(K.svg(WARN_TRI, 13f, EditorColors.ERROR, 2f))
                }
                body.add(row).growX().row()
            }
        }
        if (shown == 0) body.add(K.text(if (onlyIssues) "没有问题" else "暂无结构节点", 13f, K.subtle)).pad(dp(12f)).row()
        footer.margin(0f, dp(12f), 0f, dp(12f))
        footer.add(K.text("${nodes.size} 个节点 · ${nodes.sumOf { it.children.size }} 个字段", 11.5f, K.subtle))
    }

    companion object {
        /** 上下箭头（切换）。 */
        const val SWAP = "M7 4v16M3 8l4-4 4 4M17 20V4M13 16l4 4 4-4"
        const val STAR = "m12 3 2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z"
        const val WARN_TRI = "M12 3 2 20h20zM12 10v4M12 17h.01"
        const val FOLD = "M7 15l5-5 5 5M7 9l5-5 5 5"
        const val BRACKETS = "M8 4H5v16h3M16 4h3v16h-3"
        const val DOT = "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z"
    }
}
