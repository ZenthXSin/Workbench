package io.github.zenthxsin.rustwright.ui.editor

import io.github.zenthxsin.rustwright.ui.editor.EditorChrome

import arc.Core
import arc.scene.Element
import arc.scene.ui.layout.Cell
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.editor.api.*
import io.github.zenthxsin.rustwright.core.editor.api.*
import io.github.zenthxsin.rustwright.core.editor.impl.MemoryFileDraftStore
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import io.github.zenthxsin.rustwright.ui.designsystem.BackHandler
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.ToolRequests
import io.github.zenthxsin.rustwright.ui.kit.reactive.*
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import io.github.zenthxsin.rustwright.ui.widgets.IniHighlighter
import io.github.zenthxsin.rustwright.ui.widgets.code.CodeDocument
import io.github.zenthxsin.rustwright.ui.widgets.code.CodeEditor
import io.github.zenthxsin.rustwright.ui.widgets.code.Pos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 通用编辑器（「更多 → 编辑器」），版式参考 Sora 编辑器：
 * - 顶部工具栏：文件 ☰、撤销、重做、保存、查找、更多（格式代码 / 触发补全 / 检查 / 交给助手）
 * - 文件行：`*文件名`（未保存带星号），右侧问题计数和 `行:列`
 * - 编辑区铺满（[CodeEditor]：只画可见行、按行增量着色），下方说明栏和键盘上方的符号栏（→ + 常用符号）
 * 补全 / 幽灵字 / 说明来自语言插件（[EditorAssistance]），边打字边检查（问题面板、行号标色）。
 */
open class EditorPage(
    private val project: () -> ProjectContext?,
    private val scope: CoroutineScope,
    private val drafts: FileDraftStore = sharedDrafts,
    private val support: EditorSupport = BuiltinEditorSupport(),
    private val initialFile: File? = null,
    private val requestId: String = "editor",
    private val supportFactory: (File) -> EditorSupport = { if (support.accepts(it)) support else BuiltinEditorSupport() },
) : Table(), BackHandler, io.github.zenthxsin.rustwright.ui.kit.OwnHeader {
    private var activeSupport: EditorSupport = support
    private var activeDrafts: FileDraftStore = drafts
    val document: TextDocument get() = doc
    val editorView: CodeEditor get() = editor
    val currentFile: File? get() = file
    fun openFile(file: File) = switchTo(file)

    // 数据驱动（ui.kit.reactive）：下面几个状态是信号，标签栏 / 路径行 / 字段卡片 / 文件名 / 撤销按钮都跟着它们自动更新，
    // 不再每帧拼签名比对。赋值写法不变（属性委托）。
    private val fileS = signal<File?>(null)
    private var file: File? by fileS
    private var draft: FileDraft? = null
    private val persistence = EditorDraftPersistence(scope)
    private var savedVersion by signal(-1)
    /** 文档版本 / 光标 / 说明栏是否显示：来自编辑器和补全层，每帧只比一个值，变了才推进信号。 */
    private val docV = signal(-1)
    private val cursorS = signal(Pos(0, 0))
    private val tipShown = signal(false)
    /** 正在打字、有候选：抽屉里换成候选列表。 */
    private val candidatesOn = signal(false)
    private val doc = CodeDocument()
    private val editor = CodeEditor(doc, null) { activeSupport.color(it) }
    private var assist: EditorAssistance? = null

    private var back: () -> Unit = {}
    private val fileName = K.text("", 13f, K.muted, 500, 15f).apply { setEllipsis(true) }
    private val position = K.mono("", 12f, K.subtle, 14f)
    private val chrome = EditorChrome(editor)
    private val symbolDrawer = EditorSymbolDrawer({ activeSupport }) { editor.takeIf { file != null } }
    private val searchPanel = EditorSearchPanel({ editor.takeIf { file != null } }, project, { activeSupport.searchTemplates() }) { setSearch(false) }
    private var searchCell: Cell<Element>? = null
    private val emptyHint = Table()
    private val undoBtn = chrome.icon(PI.undo, "撤销") { editor.undo() }
    private val documents = EditorTabs(scope, object : EditorTabs.Host {
        override fun current() = file
        override fun dirty() = this@EditorPage.dirty
        override fun errors() = problems.count { it.severity == "error" }
        override fun open(file: File?) = this@EditorPage.open(file)
        override fun save(after: () -> Unit) = write(after)
        override fun leave() = back()
        override fun changed() = sidebar.refresh()
    })
    private val tabs = documents.strip
    /** 项目快照（贴图 / 音频 / ini / 标签），后台扫一次缓存；保存、新建、换项目时作废重扫。 */
    private var indexJob: Job? = null
    private val sidebar: EditorSidebar = EditorSidebar(object : EditorSidebar.Host {
        override fun projectDir() = project()?.projectDir()
        override fun projectName() = project()?.projectName() ?: ""
        override fun currentFile() = file
        override fun lines() = doc.linesSnapshot()
        override fun sectionLabel(section: String) = activeSupport.sectionLabel(section)
        override fun outline() = file?.let { activeSupport.outline(it, doc.linesSnapshot()) }.orEmpty()
        override fun cursorLine() = editor.cursor.line
        override fun errorLines() = problems.filter { it.severity == "error" && it.line > 0 }.map { it.line }.toSet()
        override fun bookmarks() = tabs.bookmarks
        // 所有文件都在这里开成标签：文本进编辑器，图片等显示预览
        override fun openFile(f: File) { switchTo(f) }
        override fun jumpTo(line: Int) { editor.setCursor(Pos(line, doc.line(line).length)); editor.revealCursor() }
        override fun menu(anchor: Element, tab: Int) = sidebarMenu(anchor, tab)
        override fun removeBookmark(f: File) { tabs.bookmarks -= f; tabs.rebuild() }
        override fun projectMenu(anchor: Element) = showProjectMenu(anchor)
    })
    private var sidebarJob: Job? = null
    /** 路径行：文件夹 › [段落] › 字段。 */
    private val crumbs = Table().left()
    private val redoBtn = chrome.icon(PI.redo, "重做") { editor.redo() }

    /** 实时检查（原编辑器边打字边检查）：文字停 700ms 后检查编辑缓冲。 */
    private var problems by signal(emptyList<EditorDiagnostic>())
    private var shownDir: File? = null
    private var checkSerial = 0

    private val host = object : EditorHost {
        override val editor get() = this@EditorPage.editor
        override val file get() = this@EditorPage.file
        override val root get() = project()?.projectDir()
        override val scope get() = this@EditorPage.scope
        override fun project() = this@EditorPage.project()
        override fun openFile(file: File) { sidebar.close(); switchTo(file) }
        override fun invalidate() = invalidateIndex()
        override fun openDesigner() = this@EditorPage.openDesigner()
    }

    private val diagnostics = EditorDiagnostics(scope, editor, { host }, { activeSupport },
        { scene != null }, ::showIssues) { found ->
        io.github.zenthxsin.rustwright.ui.kit.PerfTrace.span("lint.apply") { problems = found; Reactive.flush() }
    }
    private val preview = EditorPreview(scope, emptyHint, { host }, { scene != null })
    private val issues = diagnostics.badge
    private val fieldPanel = EditorFieldPanel(editor, scope, { host }, { activeSupport }, ::updateFieldCard)
    private val tip = fieldPanel.tip
    private val fieldDrawer = fieldPanel.drawer
    private val drawerHint = fieldPanel.drawerHint

    init {
        top()
        editor.onSave = { save() }

        // 顶栏：返回 · 结构 ……… 搜索 撤销 重做 保存 更多；下面一行打开过的文件标签，再下面一行 文件夹 › [段落] › 字段 ……… 问题数 行:列
        lateinit var moreBtn: Tap
        lateinit var penBtn: Tap
        moreBtn = chrome.icon(PI.dots, "更多") { chrome.popup(moreBtn, moreEntries()) }
        penBtn = chrome.icon(PI.pen, "辅助编辑") { chrome.popup(penBtn, penEntries()) }
        val bar = Table()
        bar.margin(0f, dp(5f), 0f, dp(6f))
        // 顶栏：≡（侧栏：项目 / 结构）……… 跳转 撤销 重做 笔（辅助编辑） ⋮
        bar.add(chrome.icon(PI.menu, "侧栏") { sidebar.open() }.apply { clearChildren(); add(K.svg(SIDEBAR_ICON, 22f, K.text, 1.8f)) }).size(dp(44f))
        bar.add().growX()
        for (e in listOf(chrome.icon(PI.search, "搜索") { openSearch() }, undoBtn, redoBtn, chrome.icon(PI.save, "保存文件") { save() }, penBtn, moreBtn)) bar.add(e).size(dp(40f))
        add(bar).growX().height(dp(52f)).row()
        add(documents.pane).growX().height(dp(40f)).padBottom(dp(4f)).row()
        add(Table(K.fill(K.line, 0f))).growX().height(dp(1f)).row()
        val crumbRow = Table()
        crumbRow.margin(0f, dp(16f), 0f, dp(16f))
        crumbRow.add(crumbs).growX().minWidth(0f).left()
        // 中文字形重心偏低，往上提一点和右边等宽的 行:列 对齐
        crumbRow.add(issues).padLeft(dp(8f)).padRight(dp(6f)).padBottom(dp(2.5f))
        crumbRow.add(position)
        // 字段抽屉的开关：有字段信息时路径行右边出现 ⌄（当前行有问题时红色）；点一下开 / 关，往下拖打开、往上拖收起
        crumbRow.add(drawerHint).size(dp(14f)).padLeft(dp(6f))
        fieldPanel.installHandle(crumbRow)
        add(crumbRow).growX().height(dp(28f)).row()
        add(Table(K.fill(K.line, 0f))).growX().height(dp(1f)).row()
        chrome.selectionMenu({ openSearch() }) { anchor -> chrome.popup(anchor, editEntries()) }

        @Suppress("UNCHECKED_CAST")
        searchCell = add(searchPanel).growX().pad(dp(6f), dp(8f), 0f, dp(8f)) as Cell<Element>
        row()
        setSearch(false)

        add(arc.scene.ui.layout.Stack(editor, emptyHint, fieldDrawer)).grow().row()
        add(Table(K.fill(K.inputBorder, 0f))).growX().height(dp(1f)).row()
        add(symbolDrawer).growX()
        addChild(sidebar)
        editor.tailRows = 15
        // 布尔字段：点值本身就切换（不移动光标、不弹键盘）
        editor.onTapAt = fieldPanel::toggleValue

        open(initialFile ?: ToolRequests.take(requestId) ?: defaultFile())
        bindReactive()
    }

    private fun editEntries(): List<EditorChrome.Entry> = chrome.editEntries(listOf(
        EditorChrome.Entry("格式化代码", PI.codeBrackets, enabled = editor.editable) { format() },
    ))

    private fun moreEntries(): List<EditorChrome.Entry> = listOf(
        EditorChrome.Entry("编辑…", PI.edit) { arc.Core.app.post { chrome.popup(moreAnchor(), editEntries()) } },
        EditorChrome.Entry("保存", PI.check, enabled = dirty) { save() },
        EditorChrome.Entry("转到指定行", PI.gotoLine) { scope.launch(ArcDispatcher) { Dialogs.input("转到指定行", "1 - ${doc.lineCount}", "${editor.cursor.line + 1}")?.toIntOrNull()?.let { chrome.gotoLine(it) } } },
        EditorChrome.Entry("自动换行", PI.wrap, checked = editor.wrap) { editor.wrap = !editor.wrap },
        EditorChrome.Entry("只读模式", PI.eye, checked = !editor.editable) { editor.editable = !editor.editable; fieldPanel.resetControls(); updateFieldCard() },
        EditorChrome.Entry("代码补全", PI.code, checked = EditorChrome.completion) {
            EditorChrome.completion = !EditorChrome.completion
            file?.let { f -> assist?.dispose(); assist = if (EditorChrome.completion) activeSupport.assistance(host, tip) else null }
        },
        EditorChrome.Entry("检查", PI.shield) { check() },
        EditorChrome.Entry("问${io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.assistant}", PI.chat) { askRust() },
        EditorChrome.Entry("关闭文件", PI.close) { file?.let { documents.close(it) } ?: back() },
    )

    /** 笔：辅助编辑。可视化编辑先占位，只在编辑单位时出现。 */
    private fun penEntries(): List<EditorChrome.Entry> {
        val c = editor.cursor; val f = file
        val cur = doc.line(c.line)
        return activeSupport.actions(host).map { EditorChrome.Entry(it.title, it.icon, enabled = it.enabled) { it.action() } } + listOf(
            EditorChrome.Entry("触发补全", PI.code, enabled = editor.editable) { assist?.open() },
            EditorChrome.Entry(if (cur.trimStart().startsWith(activeSupport.commentPrefix(f ?: File("document.txt")).trim())) "取消注释这一行" else "注释这一行", PI.code, enabled = f != null && editor.editable && cur.isNotBlank()) { chrome.toggleComment(activeSupport.commentPrefix(f!!)) },
            EditorChrome.Entry("格式化整个文件", PI.codeBrackets, enabled = f != null && editor.editable) { format() },
            EditorChrome.Entry("解释当前内容", PI.chat, enabled = f != null) { project()?.askRust("解释 ${f?.let { rel(it) }} 第 ${c.line + 1} 行：$cur") },
            EditorChrome.Entry("补全当前段落", PI.plus, enabled = f != null) { project()?.askRust("补全 ${f?.let { rel(it) }} 中当前段落，保持现有文件格式。") },
            EditorChrome.Entry("预览", PI.eye, enabled = f?.extension?.lowercase() in setOf("md", "markdown")) { preview.markdown() },
        )
    }
    /** 侧栏 ≡：新建各类文件；结构页多一个「新段落」；最后是返回工作台。 */
    private fun sidebarMenu(anchor: Element, tab: Int) {
        val ctx = project()
        val dir = ctx?.projectDir()
        val base = file?.parentFile?.takeIf { d -> dir != null && d.startsWith(dir) } ?: dir
        val entries = ArrayList<EditorChrome.Entry>()
        if (tab == 1) entries += EditorChrome.Entry("新段落", EditorSidebar.BRACKETS, enabled = file != null && editor.editable) { sidebar.close(); symbolDrawer.showPage(2) }
        entries += activeSupport.createActions(host).map { EditorChrome.Entry(it.title, it.icon, enabled = it.enabled) { it.action() } }
        entries += EditorChrome.Entry("新建文件", PI.file, enabled = base != null) { createPlain(base, folder = false) }
        entries += EditorChrome.Entry("新建文件夹", PI.folder, enabled = base != null) { createPlain(base, folder = true) }
        entries += EditorChrome.Entry("返回工作台", PI.close) { sidebar.close(); back() }
        EditorChrome.menu(anchor, entries)
    }

    /** 侧栏项目名右边的「切换」：列出所有项目（当前打勾），点了就切过去；最后是新建项目。 */
    private fun showProjectMenu(anchor: Element) {
        val ctx = project() ?: return
        val list = ctx.projects()
        val entries = list.map { p ->
            EditorChrome.Entry(p.name, PI.folder, checked = if (p.current) true else null, enabled = p.dir != null) {
                if (!p.current) { sidebar.close(); switchProject(p.id) }
            }
        } + EditorChrome.Entry("新建项目", PI.plus) {
            scope.launch(ArcDispatcher) { if (ctx.newProject()) sidebar.close() }
        }
        EditorChrome.menu(anchor, entries)
    }

    /** 切项目：当前文件有改动先存；标签 / 分组 / 书签只留新项目里的（act 里发现目录变了会打开新项目的文件）。 */
    private fun switchProject(id: String) {
        val go = { project()?.switchProject(id); Unit }
        if (dirty) write { go() } else go()
    }

    private fun createPlain(base: File?, folder: Boolean) {
        base ?: return
        scope.launch(ArcDispatcher) {
            val name = Dialogs.input(if (folder) "新建文件夹" else "新建文件", if (folder) "文件夹名字" else "文件名（如 notes.ini）", "")?.trim()?.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it } ?: return@launch
            val f = File(base, name)
            if (f.exists()) return@launch project()?.notify("$name 已经有了") ?: Unit
            val ok = withContext(Dispatchers.IO) { runCatching { if (folder) f.mkdirs() else { base.mkdirs(); f.createNewFile() } }.getOrDefault(false) }
            if (!ok) return@launch project()?.notify("建不了 $name") ?: Unit
            invalidateIndex()
            if (folder) sidebar.refresh() else { sidebar.close(); switchTo(f) }
        }
    }

    /** 项目快照作废并在后台重扫（贴图 / 继承等下拉框用）。 */
    private fun invalidateIndex() {
        sidebar.invalidateStats()
        val root = project()?.projectDir()
        indexJob?.cancel()
        indexJob = scope.launch(Dispatchers.IO) {
            runCatching { activeSupport.invalidate(root) }
            Core.app.post { if (project()?.projectDir() == root) { fieldPanel.pause(); updateFieldCard() } }
        }
    }

    private fun format() {
        val ctx = project() ?: return
        val f = file ?: return
        val text = doc.text
        val formatted = activeSupport.format(f, text)
        val changed = formatted != text
        if (changed) { val c = editor.cursor; editor.replace(Pos(0, 0), doc.end(), formatted); editor.setCursor(c) }
        ctx.notify(if (changed) "已格式化" else "已经是规范格式")
    }

    private var suspended = false
    override fun remove(): Boolean {
        suspended = true
        checkSerial++; persistence.pause(); diagnostics.pause(); fieldPanel.pause(); indexJob?.cancel(); sidebarJob?.cancel()
        persistence.retainNow(draft, activeDrafts, doc)
        assist?.dispose(); assist = null; closeDesigner(); preview.clear()
        return super.remove()
    }
    override fun bindBack(back: () -> Unit) { this.back = back }

    override fun act(delta: Float) {
        super.act(delta)
        if (suspended && scene != null) {
            suspended = false
            if (editor.visible && EditorChrome.completion) assist = activeSupport.assistance(host, tip)
            else if (file != null && !editor.visible) { emptyHint.clearChildren(); preview.show(file!!) }
            fieldPanel.pause(); invalidateIndex()
        }
        io.github.zenthxsin.rustwright.ui.kit.PerfTrace.span("assist.act") { assist?.act() }
        ToolRequests.take(requestId)?.let { open(it) }
        // 切换了项目：换成新项目里的文件
        val dir = project()?.projectDir()
        if (dir != shownDir && (file == null || dir == null || file?.startsWith(dir) != true)) open(defaultFile())
        // 外部状态桥：只比较三个值，变了才推进信号，其余界面更新都由 bindReactive() 里的副作用负责
        if (doc.version != docV.peek()) docV.value = doc.version
        if (editor.cursor != cursorS.peek()) cursorS.value = editor.cursor
        if (tip.visible != tipShown.peek()) tipShown.value = tip.visible
        val cand = assist?.isOpen == true
        if (cand != candidatesOn.peek()) candidatesOn.value = cand
        // 同一帧里就把受影响的界面更新掉（不等下一帧），打字时标签 / 路径 / 行列号不会慢一拍
        io.github.zenthxsin.rustwright.ui.kit.PerfTrace.span("flush") { Reactive.flush() }
    }

    /** 状态 → 界面的绑定：依赖的信号变了才重跑（同一帧多次变化合并成一次）。 */
    private fun bindReactive() {
        effect(this) { documents.observe(); file; problems; docV.value; Reactive.untracked { documents.rebuild() } }
        // 侧栏打开时，文档 / 光标 / 问题变了才刷新（停手 200ms 后一次）
        effect(this) { docV.value; cursorS.value; problems; Reactive.untracked { if (sidebar.isOpen) { sidebarJob?.cancel(); sidebarJob = scope.launch { delay(200); sidebar.refresh() } } } }
        crumbs.bindContent { io.github.zenthxsin.rustwright.ui.kit.PerfTrace.span("crumbs") { buildCrumbs() } }
        // 挂在页面上而不是卡片上：卡片收起时会被整个移出画面，挂在它身上就永远等不到重新显示
        effect(this) { io.github.zenthxsin.rustwright.ui.kit.PerfTrace.span("fieldCard") { updateFieldCard() } }
        fileName.bindText { file?.let { (if (dirty) "*" else "") + it.name } ?: "" }
        position.bindText { if (file == null) "" else cursorS.value.let { "${it.line + 1}:${it.col + 1}" } }
        undoBtn.bindAlpha { docV.value; if (doc.canUndo) 1f else 0.35f }
        redoBtn.bindAlpha { docV.value; if (doc.canRedo) 1f else 0.35f }
        // 改了字：检查（自带 700ms 防抖）+ 草稿保留 / 单位预览通知（停手 400ms 后在后台做）
        effect(this) {
            val v = docV.value
            val f = file ?: return@effect
            Reactive.untracked { if (v != diagnostics.checkedVersion) diagnostics.lint(); if (v != persistence.syncedVersion) persistence.schedule(doc, draft, activeDrafts, project()?.projectDir(), f, activeSupport) }
        }
    }

    private val dirty get() = file != null && docV.value != savedVersion

    /** 可视化编辑：盖在整个编辑器上；读当前缓冲（含未保存的修改）。 */
    private var design: Element? = null
    private fun openDesigner() {
        closeDesigner()
        val view = activeSupport.designer(host) { closeDesigner() } ?: return
        design = view; view.setFillParent(true); addChild(view); view.toFront()
    }
    private fun closeDesigner() { design?.let { activeSupport.closeDesigner(it); it.remove() }; design = null }

    override fun onBackRequested(leave: () -> Unit): Boolean {
        if (design != null) { closeDesigner(); return true }
        if (sidebar.isOpen) { sidebar.close(); return true }
        if (symbolDrawer.open) { symbolDrawer.animateTo(false); return true }
        if (fieldDrawer.isOpen) { fieldPanel.dismiss(); return true }
        if (!dirty) return false
        scope.launch(ArcDispatcher) {
            when (Dialogs.choose("尚未保存", "${file?.name.orEmpty()} 的修改还没保存。", listOf("取消", "放弃", "保存"))) {
                1 -> { draft?.let { activeDrafts.discard(it) }; draft = null; leave() }
                2 -> write { leave() }
            }
        }
        return true
    }

    private fun textFiles(): List<File> {
        val dir = project()?.projectDir() ?: return emptyList()
        return dir.walkTopDown().onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && TextFiles.isText(it) }
            .sortedWith(compareBy<File>({ !it.name.endsWith(".ini", true) }, { -it.lastModified() }))
            .toList()
    }

    private fun defaultFile(): File? = textFiles().firstOrNull()

    private fun rel(f: File): String = project()?.projectDir()?.let { f.relativeToOrNull(it)?.path } ?: f.name

    private fun open(f: File?) {
        closeDesigner()
        preview.clear()
        fieldPanel.resetControls()
        persistence.retainNow(draft, activeDrafts, doc)
        draft = null
        checkSerial++
        val newDir = project()?.projectDir()
        if (newDir != shownDir) {
            documents.retainProject(newDir)
            sidebar.invalidateStats()
        }
        shownDir = newDir
        file = f
        assist?.dispose()
        assist = null
        activeSupport = f?.let(supportFactory) ?: support
        activeDrafts = activeSupport.drafts ?: drafts
        invalidateIndex()
        tip.clearChildren(); tip.visible = false
        problems = emptyList()
        diagnostics.pause()
        editor.lineMarks = emptyMap()
        editor.marks = emptyList()
        editor.matches = emptyList()
        if (searchPanel.visible) setSearch(false)
        emptyHint.clearChildren()
        if (f == null) {
            editor.setText("")
            editor.visible = false
            fileName.setText("")
            position.setText("")
            diagnostics.setCounts(-2, -2)
            emptyHint.add(K.text(if (shownDir == null) "未选择项目" else "项目里还没有文本文件", 15f, K.muted)).row()
            emptyHint.add(K.text(if (shownDir == null) "先在「项目」里选一个" else "新建文本文件，或从「文件管理」导入", 13f, K.subtle)).padTop(dp(6f))
            return
        }
        documents.opened(f)
        if (!TextFiles.isText(f)) {
            // 图片 / 其他非文本：也是一个标签，编辑区换成预览
            editor.setText(""); savedVersion = doc.version
            editor.visible = false
            diagnostics.setCounts(-2, -2)
            preview.show(f)
            return
        }
        editor.setHighlighter(activeSupport.highlighter(f))
        editor.visible = true
        val root = shownDir ?: return
        val opened = runCatching { activeDrafts.open(root, f.relativeTo(root).invariantSeparatorsPath) }
        if (opened.isFailure) { project()?.notify("打开失败：${opened.exceptionOrNull()?.message}"); file = null; editor.visible = false; return }
        draft = opened.getOrThrow()
        editor.setText(draft!!.text)
        savedVersion = if (draft!!.dirty) -1 else doc.version
        diagnostics.pause()
        if (EditorChrome.completion) assist = activeSupport.assistance(host, tip)
    }

    private fun showIssues() {
        val ctx = project() ?: return
        val f = file ?: return
        EditorIssuesSheet.show(ctx, f.name, problems, onJump = { line, column -> editor.jumpTo(line, column) }) { report ->
            ctx.askRust("帮我修好 ${rel(f)} 的检查问题：\n$report")
        }
    }

    private fun setSearch(on: Boolean) {
        searchPanel.visible = on
        // 收起时把格子清空，不占高度
        searchCell?.setElement(if (on) searchPanel else null)?.padTop(if (on) dp(6f) else 0f)
        invalidateHierarchy()
    }

    private fun openSearch() {
        if (file == null) return
        setSearch(true)
        searchPanel.focus()
    }

    /** 「更多」（原编辑器 EditorStatusBarMoreMenu + 原编辑器底部按钮）：格式代码、触发补全、检查、交给助手。 */
    private fun write(after: () -> Unit = {}) {
        val f = file ?: return
        val version = doc.version
        val lines = doc.linesSnapshot()
        val baseline = draft ?: return
        val store = activeDrafts
        persistence.save(baseline, store, lines) { result ->
            result.fold({ saved ->
                if (file == f) { savedVersion = version; draft = saved; persistence.pause() }
                invalidateIndex()
                if (file == f && doc.version == version) after()
            }, { project()?.notify("保存失败：${it.message}") })
        }
    }

    private fun save() {
        if (file == null) return
        // 不弹通知：横幅会盖住顶栏好几秒（点不了侧栏 / 撤销），标签上的未保存圆点消失就是保存成功
        write()
    }

    private fun check() {
        val ctx = project() ?: return
        val f = file ?: return ctx.notify("先打开一个文件")
        val dir = ctx.projectDir() ?: return
        val relative = f.relativeToOrNull(dir)?.path ?: return ctx.notify("文件不在当前项目里")
        val serial = ++checkSerial
        ctx.notify("正在检查 ${f.name}…")
        write {
            scope.launch {
                val found = runCatching { activeSupport.problems(dir, f, doc.text) }
                Core.app.post {
                    // 检查可以继续完成，但离开编辑器、切文件/项目后不能覆盖正在使用的页面。
                    if (serial != checkSerial || file != f || scene == null || ctx.projectDir() != dir) return@post
                    found.fold({ showReport(f.name, it) }, { ctx.notify("检查失败：${it.message}") })
                }
            }
        }
    }

    /** 「检查」的结果用和路径行问题计数同一个面板：按段落分组，点一条跳到那一行。 */
    private fun showReport(name: String, found: List<EditorDiagnostic>) {
        val ctx = project() ?: return
        val f = file ?: return
        problems = found
        EditorIssuesSheet.show(ctx, name, found, onJump = { line, column -> editor.jumpTo(line, column) }) { report ->
            ctx.askRust("帮我修好 ${rel(f)} 的检查问题：\n$report")
        }
    }

    /** 切到另一个文件：当前有改动就先存。 */
    private fun switchTo(f: File) {
        if (f == file) return
        if (dirty) write { open(f) } else open(f)
    }

    /** 路径行：文件夹 › [段落] › 字段（光标所在）。 */
    private fun buildCrumbs() {
        val f = file
        docV.value
        val c = cursorS.value
        var section: String? = null
        if (f != null) {
            var l = c.line
            while (l >= 0) {
                val t = doc.line(l).trim()
                if (t.startsWith("[") && t.contains("]")) { section = t.substring(0, t.indexOf(']') + 1); break }
                l--
            }
        }
        val cur = if (f != null) doc.line(c.line) else ""
        val sep = cur.indexOfFirst { it == ':' || it == '=' }
        val key = if (sep > 0 && !cur.trimStart().startsWith("#") && !cur.trimStart().startsWith("[")) cur.substring(0, sep).trim().takeIf { it.isNotEmpty() } else null
        val dir = f?.parentFile?.let { p -> project()?.projectDir()?.let { p.relativeToOrNull(it)?.path } }?.takeIf { it.isNotEmpty() }?.replace("/", " / ") ?: if (f != null) "根目录" else ""
        if (f == null) return
        fun sepMark() = crumbs.add(K.text("›", 12f, K.subtle, lh = 12f)).pad(0f, dp(6f), dp(1f), dp(6f))
        crumbs.add(K.text(dir, 12f, K.subtle, lh = 12f).apply { setEllipsis(true) }).minWidth(0f).padBottom(dp(1f))
        section?.let { sepMark(); crumbs.add(K.mono(it, 12f, IniHighlighter.color(IniHighlighter.SECTION), 14f)) }
        key?.let { sepMark(); crumbs.add(K.text(it, 12f, K.muted, lh = 12f).apply { setEllipsis(true) }).minWidth(0f).padBottom(dp(1f)) }
    }

    private fun updateFieldCard() {
        docV.value
        fieldPanel.update(EditorFieldPanel.State(cursorS.value.line, tipShown.value, problems,
            if (candidatesOn.value) assist?.candidates else null))
    }

    private fun moreAnchor(): Element = find<Element>("更多") ?: this

    private fun askRust() {
        val ctx = project() ?: return
        val f = file
        ctx.askRust(if (f != null) "看看 ${rel(f)}，帮我改：" else "帮我编辑一个文本文件")
    }

    companion object {
        val sharedDrafts: FileDraftStore = MemoryFileDraftStore()
        const val MAX_TABS = 8
        /** 自动收起前等一小会儿（秒）：候选刚关、字段说明还没回来的那一瞬间不让抽屉缩回去又弹出来。 */
        const val CLOSE_DELAY = 0.2f
        /** 侧栏按钮：三条长短不一的横线。 */
        const val SIDEBAR_ICON = "M4 6h16M4 12h11M4 18h7"
    }
}
