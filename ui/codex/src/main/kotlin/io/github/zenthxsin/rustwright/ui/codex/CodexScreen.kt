package io.github.zenthxsin.rustwright.ui.codex

import arc.Core
import arc.graphics.Color
import arc.scene.event.Touchable
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Cell
import arc.scene.ui.layout.Stack
import arc.scene.ui.layout.Table
import arc.util.Align
import arc.util.Time
import io.github.zenthxsin.rustwright.core.codex.CodexApproval
import io.github.zenthxsin.rustwright.core.codex.CodexConversation
import io.github.zenthxsin.rustwright.core.codex.CodexProject
import io.github.zenthxsin.rustwright.core.codex.CodexTurn
import io.github.zenthxsin.rustwright.core.codex.CodexWorkbench
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import io.github.zenthxsin.rustwright.ui.designsystem.Lucide
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import io.github.zenthxsin.rustwright.ui.widgets.Popover
import io.github.zenthxsin.rustwright.ui.widgets.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 工作台与 App 其他部分的接口。 */
interface CodexHost {
    /** 还不能发送任务时的说明与处理按钮（如「先填写令牌」「初始化 Linux」）；可以时返回 null。 */
    fun blocker(): Pair<String, Pair<String, () -> Unit>>?
    fun openSettings()
    /** 「环境」：打开内置 Linux（终端页）。 */
    fun openEnvironment()
    /** 侧栏底部的返回（回首页）。 */
    fun back()
    /** 当前账号显示名。 */
    val accountName: String
    fun copy(text: String)
    /** 导出文件（Markdown / JSON）。 */
    suspend fun export(fileName: String, content: String, mimeType: String)
    /** 新建项目（模组），返回项目；取消返回 null。 */
    suspend fun createProject(): CodexProject?
    /** 项目内文件（相对路径）。 */
    fun listFiles(project: CodexProject): List<String>
    fun openFile(project: CodexProject, relativePath: String)
    /** 选一个文件作为附件（复制进 Linux），取消返回 null。 */
    suspend fun pickAttachment(project: CodexProject): CodexAttachment?
    suspend fun pickAttachments(project: CodexProject): List<CodexAttachment> = listOfNotNull(pickAttachment(project))
    /** 项目内文件大小（字节）。 */
    fun fileSize(project: CodexProject, relativePath: String): Long?
    /** 从手机选文件放进项目的 [dir] 目录。 */
    suspend fun importFile(project: CodexProject, dir: String): Boolean
    /** 在项目里新建空文件。 */
    fun createFile(project: CodexProject, relativePath: String): Boolean
}

/**
 * 助手工作台，按 new-api 在线 Codex 页面（web/src/features/codex，codex-chatgpt 主题）的手机布局逐项翻译：
 * 顶栏 56（菜单 40 / 标题 14px 500 + 项目名 12px / 查看变更、新建任务、对话选项），
 * 新建任务页（26px 标题、说明、项目与运行位置、输入框），会话（content padding 16 16 20），
 * 输入区（待执行消息、运行位置、输入框），左侧项目列表抽屉（宽 320、渐变底）。
 */
class CodexScreen(
    private val wb: CodexWorkbench,
    private val host: CodexHost,
    private val scope: CoroutineScope,
) : Stack() {

    private var currentId: String? = wb.conversations.filter { !it.archived }.maxByOrNull { it.updatedAt }?.id
    private var projectId: String? = currentId?.let { wb.conversation(it)?.projectId } ?: wb.projects.firstOrNull { !it.archived }?.id

    private val main = Table()
    private val header = Table()
    private val content = Table()
    private val list = Table().top().left()
    private val pane = ScrollPane(list).apply { setScrollingDisabled(true, false); setFadeScrollBars(true); setOverscroll(false, false) }
    private val composerArea = Table().top().left()
    private val drawer = Table()
    private val overlay = Table()

    private val composer = CodexComposer(
        wb, { conversation },
        onSubmit = { text, action, attachments -> submit(text, action, attachments) },
        onStop = { conversation?.let { wb.interrupt(it.id) } },
        onAttach = { add -> project?.let { p -> scope.launch(ArcDispatcher) { host.pickAttachments(p).forEach(add) } } },
    )

    private val uiState = CodexTurnView.UiState()
    private val turnCells = ArrayList<Triple<String, Long, Cell<arc.scene.Element>>>()
    private var tail: Table? = null
    private var shownKey: String? = "-"
    private var seenVersion = -1
    private var tailSig = -1L
    private var areaSig = ""
    private var lastRender = 0f
    private var stickToBottom = true

    init {
        add(main)
        main.background(RwTheme.solid(Css.bg))
        main.top()
        main.add(header).growX().height(dp(56f)).row()
        main.add(Css.rule()).growX().height(dp(1f)).row()
        main.add(content).grow()
        drawer.visible = false
        overlay.visible = false
        add(drawer)
        add(overlay)
        list.margin(dp(16f), dp(16f), dp(20f), dp(16f))
        update { sync() }
    }

    private val conversation: CodexConversation? get() = currentId?.let { wb.conversation(it) }
    private val project: CodexProject? get() = (conversation?.projectId ?: projectId)?.let { wb.project(it) }

    // region 顶栏

    /** .chatgpt-header：高 56、padding 0 12、gap 12、底边线。 */
    private fun buildHeader() {
        header.clearChildren()
        header.left()
        header.margin(0f, dp(12f), 0f, dp(12f))
        header.add(Css.iconButton(Lucide.menu, 16f, Css.text, 12, "打开项目列表") { openDrawer() }).size(dp(40f))
        val c = conversation
        if (c == null) {
            header.add(Css.label("新建任务", 14f, Css.text, 500, 20f)).growX().left().padLeft(dp(8f))
            header.add(Css.ghost(10) { host.openEnvironment() }.apply {
                name = "环境"
                margin(0f, dp(8f), 0f, dp(8f))
                add(Css.label("环境", 14f, Css.text))
            }).height(dp(24f))
            header.add(avatar()).size(dp(24f)).padLeft(dp(8f))
        } else {
            header.add(Table().left().apply {
                add(Css.label("", 14f, Css.text, 500, 20f).apply { setEllipsis(true); update { setText(conversation?.title ?: "") } }).left().growX().minWidth(0f).row()
                add(Css.label("", 12f, Css.muted, lineHeight = 16f).apply { setEllipsis(true); update { setText(project?.name ?: "") } }).left().growX().minWidth(0f)
            }).growX().minWidth(0f).padLeft(dp(8f))
            header.add(Css.ghost(999) { openReview() }.apply {
                name = "查看变更"
                margin(0f, dp(8f), 0f, dp(8f))
                add(Css.icon(Lucide.gitCompare, 16f))
                // 打开时按钮带浅灰底（网页 data-active）
                val on = RwTheme.round(12, Css.hover)
                update { if (reviewOpen) background(on) }
            }).height(dp(24f)).padLeft(dp(8f))
            header.add(Css.iconButton(Lucide.squarePen, 16f, Css.text, 12, "新建任务") { newTask() }).size(dp(28f)).padLeft(dp(8f))
            header.add(Css.iconButton(Lucide.ellipsis, 16f, Css.text, 12, "对话选项") { anchor -> conversationOptions(anchor) }).size(dp(28f)).padLeft(dp(8f))
        }
    }

    /** 头像：24px 圆，首字母 11px 600 白字，#d44e74 底。 */
    private fun avatar() = Table(RwTheme.round(12, Color.valueOf("d44e74"))).apply {
        touchable = Touchable.enabled
        add(Css.label(host.accountName.take(1).uppercase().ifEmpty { "锈" }, 11f, Color.white, 600))
        clicked { host.openSettings() }
    }

    /** 对话选项：导出 Markdown / JSON（圆角 14、padding 4，行 padding 4 6、下载图标 16）。 */
    private fun conversationOptions(anchor: arc.scene.Element) {
        val c = conversation ?: return
        var close: () -> Unit = {}
        val menu = Table().apply {
            margin(dp(4f))
            listOf("Markdown" to "md", "JSON" to "json").forEach { (label, ext) ->
                add(Css.ghost(8) {
                    close()
                    scope.launch(ArcDispatcher) {
                        val body = if (ext == "md") exportMarkdown(c) else exportJson(c)
                        host.export("${c.title.take(40)}.$ext", body, if (ext == "md") "text/markdown" else "application/json")
                    }
                }.apply {
                    left()
                    margin(dp(4f), dp(6f), dp(4f), dp(6f))
                    add(Css.icon(Lucide.download, 16f)).padRight(dp(8f))
                    add(Css.para("将对话导出为 $label", 14f, Css.text, lineHeight = 20f)).growX()
                }).growX().minHeight(dp(48f)).row()
            }
        }
        close = Popover.show(anchor, menu, above = false, alignEnd = true, width = dp(128f))
    }

    // endregion

    // region 渲染

    private fun sync() {
        // 当前对话被删除时退回新建任务
        if (currentId != null && conversation == null) currentId = null
        val c = conversation
        val key = (c?.id ?: "new:" + projectId) + "/" + RwTheme.dark + "/" + reviewOpen
        if (key != shownKey) {
            shownKey = key
            buildHeader()
            content.clearChildren()
            turnCells.clear(); tail = null; tailSig = -1; seenVersion = -1; areaSig = ""
            if (c == null) buildNewTask() else if (reviewOpen) buildReview() else buildConversation()
            stickToBottom = true
        }
        if (c == null || reviewOpen) return
        composer.compact = true
        if (wb.version == seenVersion) return
        if (Time.time - lastRender < 5f && c.status.active) return
        lastRender = Time.time
        seenVersion = wb.version
        renderTurns(c)
        renderComposerArea(c)
    }

    /** 新建任务页：padding 32 16 24，内容垂直居中；标题 26px 500，说明 14px 500 #666，margin-bottom 28。 */
    private fun buildNewTask() {
        composer.compact = false
        val page = Table().top()
        page.margin(dp(32f), dp(16f), dp(24f), dp(16f))
        val inner = Table().top()
        inner.add(Css.label("你想构建什么？", 26f, Css.text, 500, 33.8f)).center().row()
        inner.add(Css.para("选择项目和运行位置，然后为 Codex 分配任务。", 14f, Css.muted, 500, 20f).apply { setAlignment(Align.center) }).growX().padTop(dp(12f)).row()
        inner.add(contextRow(newTask = true)).left().padTop(dp(28f)).padLeft(dp(2f)).row()
        host.blocker()?.let { (text, action) ->
            inner.add(Table(RwTheme.card(RwTheme.Colors.warningSoft, RwTheme.Colors.warningSoft, 12)).apply {
                margin(dp(10f), dp(12f), dp(10f), dp(12f))
                add(Css.para(text, 13f, RwTheme.Colors.warning, lineHeight = 20f)).growX()
                add(Css.ghost(999) { action.second() }.apply { margin(0f, dp(10f), 0f, dp(10f)); add(Css.label(action.first, 13f, RwTheme.Colors.warning, 500)) }).height(dp(32f)).padLeft(dp(8f))
            }).growX().padTop(dp(8f)).row()
        }
        inner.add(composer).growX().padTop(dp(8f))
        page.add().expandY().row()
        page.add(inner).growX().row()
        page.add().expandY()
        content.add(ScrollPane(page).apply { setScrollingDisabled(true, false); setFadeScrollBars(true); setOverscroll(false, false) }).grow()
    }

    private fun buildConversation() {
        list.clearChildren()
        content.add(pane).grow().row()
        content.add(composerArea).growX()
    }

    /**
     * 运行位置行（.codex-task-context-row）：项目（新建页）+ 虚拟环境，各为图标 16 + 13px 文字 + 下拉 14。
     */
    private fun contextRow(newTask: Boolean) = Table().left().apply {
        fun item(glyph: Char, text: () -> String, name: String, onClick: (Table) -> Unit) =
            Css.ghost(8, onClick).apply {
                this.name = name
                margin(0f, dp(8f), 0f, dp(8f))
                add(Css.icon(glyph, 16f)).padRight(dp(8f))
                val l = Css.label("", 13f, Css.text, lineHeight = 20f)
                add(l)
                add(Css.icon(Lucide.chevronDown, 14f, Css.muted)).padLeft(dp(8f))
                update { val t = text(); if (l.text.toString() != t) { l.setText(t); invalidateHierarchy() } }
            }
        if (newTask) add(item(Lucide.folder, { project?.name ?: "选择项目" }, "选择项目") { anchor -> pickProject(anchor) }).height(dp(36f)).padRight(dp(2f))
        add(item(Lucide.container, { "Alpine" }, "运行位置") { host.openEnvironment() }).height(dp(36f))
    }

    private fun pickProject(anchor: arc.scene.Element) {
        val projects = wb.projects.filter { !it.archived }.sortedByDescending { it.updatedAt }
        val options = projects.map { Popover.Option(it.name, it.path, it.id == project?.id) } + Popover.Option("新建项目…")
        Popover.menu(anchor, options, above = false) { i ->
            if (i < projects.size) { projectId = projects[i].id; shownKey = "-" }
            else scope.launch(ArcDispatcher) { createProject() }
        }
    }

    private fun renderTurns(c: CodexConversation) {
        val atBottom = pane.scrollY >= pane.maxY - dp(40f)
        val visible = c.turns
        if (visible.isEmpty() && turnCells.isEmpty() && list.children.isEmpty) {
            list.add(Css.label("有什么可以帮忙的？", 26f, Css.text, 500, 33.8f)).expandX().center().padTop(dp(120f)).row()
        }
        if (visible.isNotEmpty() && turnCells.isEmpty()) list.clearChildren()
        visible.forEachIndexed { i, t ->
            val running = c.status.active && t === visible.last()
            val sig = signature(t, running)
            if (i < turnCells.size) {
                val (id, old, cell) = turnCells[i]
                if (id != t.id || old != sig) { cell.setElement(turnView(t, running)); turnCells[i] = Triple(t.id, sig, cell) }
            } else {
                tail?.remove()
                @Suppress("UNCHECKED_CAST")
                val cell = list.add(turnView(t, running) as arc.scene.Element).growX().padTop(if (i == 0) 0f else dp(24f)) as Cell<arc.scene.Element>
                list.row()
                turnCells += Triple(t.id, sig, cell)
            }
        }
        val tSig = tailSignature(c)
        if (tSig != tailSig) {
            tailSig = tSig
            tail?.remove()
            tail = buildTail(c).also { list.add(it).growX().padTop(dp(12f)).row() }
        } else tail?.let { if (it.parent == null) list.add(it).growX().padTop(dp(12f)).row() }
        list.invalidateHierarchy()
        if (atBottom || stickToBottom) {
            stickToBottom = false
            // 富文本在排版时才知道行数，等两帧布局稳定后再贴底
            pane.layout(); pane.setScrollYForce(pane.maxY)
            Core.app.post { Core.app.post { pane.layout(); pane.setScrollYForce(pane.maxY) } }
        }
    }

    private fun turnView(t: CodexTurn, running: Boolean) = CodexTurnView(t, running, { Core.scene.width - dp(32f) }, uiState, turnActions)

    private val turnActions = object : CodexTurnView.Actions {
        override fun copy(text: String) { host.copy(text); Toasts.show("已复制") }
        override fun edit(turn: CodexTurn, text: String) { composer.setDraft(text) }
        override fun retry(turn: CodexTurn, text: String) { conversation?.let { wb.send(it.id, text) }; stickToBottom = true }
        override fun delete(turn: CodexTurn, role: String) { conversation?.let { wb.deleteMessage(it.id, turn.id, role) } }
        override fun changed() { for (i in turnCells.indices) turnCells[i] = Triple(turnCells[i].first, 0L, turnCells[i].third); seenVersion = -1; lastRender = 0f }
    }

    private fun signature(t: CodexTurn, running: Boolean): Long {
        var h = t.status.hashCode().toLong() * 31 + t.items.size
        for (it in t.items) h = h * 31 + it.text.length + it.output.length * 7 + it.changes.size * 13 + (it.status?.hashCode() ?: 0) + (it.result?.length ?: 0)
        h = h * 31 + (t.diff?.length ?: 0) + t.plan.hashCode() + (t.error?.hashCode() ?: 0) + t.deletedRoles.hashCode()
        return h * 2 + if (running) 1 else 0
    }

    private fun tailSignature(c: CodexConversation): Long =
        ((c.approvals.map { it.id }.hashCode().toLong() * 31 + (c.lastError?.hashCode() ?: 0)) * 31 + c.status.ordinal)

    /** 会话末尾：审批卡片；运行中且无审批时「● Codex 正在处理…」（13px #666、10px 黑点）；运行错误。 */
    private fun buildTail(c: CodexConversation) = Table().top().left().apply {
        c.approvals.forEach { a ->
            val changes = if (a.kind == CodexApproval.Kind.FILE_CHANGE) {
                val itemId = a.params["itemId"]?.toString()?.trim('"')
                c.turns.flatMap { it.items }.firstOrNull { it.id == itemId }?.changes.orEmpty()
            } else emptyList()
            add(CodexApprovalCard(a, changes) { answer -> wb.answer(a.id, answer) }).growX().padBottom(dp(12f)).row()
        }
        if (c.status.active && c.approvals.isEmpty()) {
            add(Table().left().apply {
                add(Pulse()).size(dp(10f)).padRight(dp(10f))
                add(Css.label("Codex 正在处理…", 13f, Css.muted, lineHeight = 22f))
            }).left().minHeight(dp(32f)).row()
        }
        val err = c.lastError
        if (err != null && !c.status.active && c.turns.lastOrNull()?.error == null) {
            val danger = RwTheme.Colors.danger
            add(Table(RwTheme.card(danger.cpy().a(0.05f), danger.cpy().a(0.2f), 12)).left().apply {
                margin(dp(12f))
                add(Css.icon(Lucide.circleAlert, 16f, danger)).top().padRight(dp(8f))
                add(Css.para(err, 14f, danger, lineHeight = 24f)).growX().minWidth(0f)
            }).growX().row()
        }
    }

    /** 输入区（.chatgpt-composer-wrap，padding 8 12 12）：待执行消息、运行位置、输入框。 */
    private fun renderComposerArea(c: CodexConversation) {
        val sig = c.queue.joinToString { it.id } + "/" + c.id
        if (sig == areaSig) return
        areaSig = sig
        composerArea.clearChildren()
        composerArea.margin(dp(8f), dp(12f), dp(12f), dp(12f))
        if (c.queue.isNotEmpty()) composerArea.add(CodexQueuedCard(c.queue) { id -> wb.cancelQueued(c.id, id) }).growX().padBottom(dp(8f)).row()
        composerArea.add(contextRow(newTask = false)).left().padBottom(dp(4f)).row()
        composerArea.add(composer).growX()
    }

    // endregion

    // region 提交

    private fun submit(text: String, action: CodexComposer.Action, attachments: List<CodexAttachment>): Boolean {
        host.blocker()?.let { (msg, act) ->
            scope.launch(ArcDispatcher) { if (Dialogs.confirm("还不能开始", msg, ok = act.first)) act.second() }
            return false
        }
        val c = conversation ?: run {
            val p = project ?: run { scope.launch(ArcDispatcher) { createProject() }; return false }
            wb.newConversation(p.id, composer.pendingMode, composer.pendingModel ?: wb.defaultModel(), composer.pendingEffort).also { currentId = it.id }
        }
        val images = attachments.filter { it.image }.map { it.path }
        val files = attachments.filter { !it.image }
        val prompt = if (files.isEmpty()) text else text + "\n\n附件：\n" + files.joinToString("\n") { "- ${it.path}" }
        stickToBottom = true
        when (action) {
            CodexComposer.Action.SEND -> wb.send(c.id, prompt, images = images)
            CodexComposer.Action.STEER -> wb.send(c.id, prompt, steer = true, images = images)
            CodexComposer.Action.INTERRUPT -> wb.interruptAndSend(c.id, prompt, images)
        }
        return true
    }

    private fun newTask() {
        conversation?.let { composer.pendingMode = it.mode; composer.pendingModel = it.model; composer.pendingEffort = it.effort }
        currentId = null
        drawer.visible = false
    }

    private suspend fun createProject(): CodexProject? {
        val p = host.createProject() ?: return null
        projectId = p.id; currentId = null; shownKey = "-"
        if (drawer.visible) openDrawer()
        return p
    }

    // endregion

    // region 侧栏抽屉

    /**
     * 侧栏（.chatgpt-sidebar，手机抽屉宽 320、背景渐变、遮罩 40% 黑）：
     * 顶部品牌 + 搜索 / 收起（44）；新建任务、环境、连接（44 行、圆角 8）；
     * 「项目」分组：项目行（44、圆角 12、选中 #e9e9eb）与缩进的对话行；「最近任务」；底部返回、头像、设置。
     */
    private fun openDrawer() {
        drawer.clearChildren()
        drawer.visible = true
        val panel = Table(GradientDrawable({ RwTheme.Colors.sidebarFrom }, { RwTheme.Colors.sidebarTo })).top().left()
        val scrim = Table(RwTheme.solid(Color(0f, 0f, 0f, 0.4f))).apply { touchable = Touchable.enabled; clicked { drawer.visible = false } }
        drawer.add(panel).width(minOf(dp(320f), Core.scene.width * 0.85f)).growY()
        drawer.add(scrim).grow()

        panel.add(Table().left().apply {
            margin(0f, dp(16f), 0f, dp(12f))
            add(Css.icon(Lucide.hammer, 20f)).padRight(dp(8f))
            add(Css.label(io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.assistant, 14f, Css.text, 500, 20f)).growX().left()
            add(Css.iconButton(Lucide.search, 16f, Css.text, 8, "搜索") { Toasts.show("搜索还没做") }).size(dp(44f))
            add(Css.iconButton(Lucide.panelLeftClose, 20f, Css.text, 8, "收起侧栏") { drawer.visible = false }).size(dp(44f)).padLeft(dp(8f))
        }).growX().height(dp(56f)).row()

        val body = Table().top().left()
        panel.add(ScrollPane(body).apply { setScrollingDisabled(true, false); setFadeScrollBars(true); setOverscroll(false, false) }).grow().row()

        fun nav(glyph: Char, iconPx: Float, text: String, onClick: () -> Unit) = Css.ghost(8) { onClick() }.apply {
            left(); name = text
            margin(0f, dp(12f), 0f, dp(12f))
            add(Css.icon(glyph, iconPx)).width(dp(iconPx)).padRight(dp(if (iconPx > 16f) 6f else 10f))
            add(Css.label(text, 14f, Css.text, lineHeight = 20f))
        }
        body.add(nav(Lucide.squarePen, 20f, "新建任务") { newTask() }).growX().height(dp(44f)).pad(0f, dp(8f), dp(2f), dp(8f)).row()
        body.add(nav(Lucide.cloud, 16f, "环境") { drawer.visible = false; host.openEnvironment() }).growX().height(dp(44f)).pad(0f, dp(8f), dp(2f), dp(8f)).row()
        body.add(nav(Lucide.plug, 16f, "连接") { drawer.visible = false; host.openSettings() }).growX().height(dp(44f)).pad(0f, dp(8f), dp(2f), dp(8f)).row()

        // 项目分组标题
        body.add(Table().left().apply {
            add(Css.label("项目", 12f, Css.muted, 500, 20f)).padRight(dp(6f))
            add(Css.icon(Lucide.chevronDown, 14f, Css.muted))
            add().growX()
            add(Css.iconButton(Lucide.listChecks, 16f, Css.text, 8, "批量管理") { Toasts.show("批量管理还没做") }).size(dp(36f)).padRight(dp(2f))
            add(Css.iconButton(Lucide.folderPlus, 16f, Css.text, 8, "新建项目") { scope.launch(ArcDispatcher) { createProject() } }).size(dp(36f))
            add(Css.iconButton(Lucide.archive, 16f, Css.text, 8, "已归档") { showArchived = !showArchived; openDrawer() }).size(dp(36f)).padLeft(dp(2f))
        }).growX().pad(dp(8f), dp(20f), dp(2f), dp(12f)).row()

        val projects = wb.projects.filter { !it.archived }.sortedByDescending { it.updatedAt }
        if (projects.isEmpty()) body.add(Css.para("还没有项目，点上面的文件夹按钮新建。", 12f, Css.muted, lineHeight = 16f)).growX().pad(dp(8f), dp(20f), dp(8f), dp(20f)).row()
        projects.forEach { p ->
            val selected = p.id == project?.id
            body.add(Table(if (selected) RwTheme.round(12, Css.selected) else null).left().apply {
                touchable = Touchable.enabled
                add(Table().apply { add(Css.icon(if (selected) Lucide.folderOpen else Lucide.folder, 16f)) }).size(dp(44f))
                add(Css.label(p.name, 14f, Css.text, lineHeight = 20f).apply { setEllipsis(true) }).growX().minWidth(0f)
                add(Css.iconButton(Lucide.ellipsis, 16f, Css.text, 999, "项目选项") { scope.launch(ArcDispatcher) { projectMenu(p) } }).size(dp(36f))
                add(Css.iconButton(Lucide.plus, 16f, Css.text, 12, "新对话") { projectId = p.id; newTask() }).size(dp(36f)).padLeft(dp(2f)).padRight(dp(4f))
                clicked { projectId = p.id; currentId = null; drawer.visible = false }
            }).growX().height(dp(44f)).pad(dp(2f), dp(8f), 0f, dp(8f)).row()
            wb.conversationsOf(p.id).filter { showArchived || !it.archived }.take(30).forEach { c ->
                val sel = c.id == currentId
                body.add(Table(if (sel) RwTheme.round(12, Css.selected) else null).left().apply {
                    touchable = Touchable.enabled
                    name = "对话:" + c.title
                    add(Table().left().apply {
                        margin(0f, dp(12f), 0f, dp(12f))
                        if (c.status.active) add(Spinner(14f)).size(dp(14f)).padRight(dp(8f))
                        add(Css.label(c.title, 14f, if (c.archived) Css.muted else Css.text, lineHeight = 20f).apply { setEllipsis(true) }).growX().minWidth(0f)
                    }).growX().minWidth(0f)
                    add(Css.iconButton(Lucide.ellipsis, 14f, Css.text, 12, "对话选项") { scope.launch(ArcDispatcher) { conversationMenu(c) } }).size(dp(36f)).padRight(dp(4f))
                    clicked { currentId = c.id; projectId = p.id; drawer.visible = false }
                }).growX().height(dp(44f)).pad(dp(2f), dp(32f), 0f, dp(8f)).row()
            }
        }
        body.add(Css.ghost(12) { showArchived = !showArchived; openDrawer() }.apply {
            left()
            margin(0f, dp(12f), 0f, dp(12f))
            add(Css.icon(Lucide.archive, 14f, Css.muted)).padRight(dp(8f))
            add(Css.label(if (showArchived) "隐藏已归档对话" else "显示已归档对话", 12f, Css.muted, lineHeight = 16f))
        }).growX().height(dp(44f)).pad(dp(2f), dp(32f), 0f, dp(8f)).row()

        // 最近任务
        body.add(Table().left().apply {
            add(Css.label("最近任务", 12f, Css.muted, 500, 20f)).growX().left()
        }).growX().pad(dp(24f), dp(20f), dp(4f), dp(20f)).row()
        wb.conversations.filter { !it.archived }.sortedByDescending { it.updatedAt }.take(20).forEach { c ->
            val sel = c.id == currentId
            body.add(Table(if (sel) RwTheme.round(8, Css.selected) else null).left().apply {
                touchable = Touchable.enabled
                margin(0f, dp(12f), 0f, dp(4f))
                add(Css.label(c.title, 14f, Css.text, lineHeight = 20f).apply { setEllipsis(true) }).growX().minWidth(0f)
                add(Css.iconButton(Lucide.ellipsis, 16f, Css.text, 10, "任务选项") { scope.launch(ArcDispatcher) { conversationMenu(c) } }).size(dp(24f)).padRight(dp(4f))
                clicked { currentId = c.id; projectId = c.projectId; drawer.visible = false }
            }).growX().height(dp(44f)).pad(dp(2f), dp(8f), 0f, dp(8f)).row()
        }

        // 底部
        panel.add(Css.rule(Css.border.cpy().a(0.5f))).growX().height(dp(1f)).row()
        panel.add(Table().left().apply {
            margin(dp(6f), dp(8f), dp(8f), dp(12f))
            add(Css.iconButton(Lucide.arrowLeft, 16f, Css.text, 8, "返回") { drawer.visible = false; host.back() }).size(dp(44f))
            add(avatar()).size(dp(24f)).padLeft(dp(8f))
            add(Table().left().apply {
                add(Css.label(host.accountName.ifEmpty { io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.assistant }, 14f, Css.text, 500, 20f).apply { setEllipsis(true) }).left().growX().minWidth(0f).row()
                add(Css.label(io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.name, 12f, Css.muted, lineHeight = 16f)).left()
            }).growX().minWidth(0f).padLeft(dp(8f))
            add(Css.iconButton(Lucide.settings, 16f, Css.text, 12, "设置") { drawer.visible = false; host.openSettings() }).size(dp(28f))
        }).growX().row()
    }

    private var showArchived = false

    private suspend fun projectMenu(p: CodexProject) {
        when (Dialogs.choose(p.name, p.path, listOf("新对话", "重命名", "归档", "移除"), highlight = -1)) {
            0 -> { projectId = p.id; newTask() }
            1 -> Dialogs.input("重命名项目", initial = p.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { wb.renameProject(p.id, it) }
            2 -> wb.setProjectArchived(p.id, true)
            3 -> if (Dialogs.confirm("移除项目", "只移除项目记录和对话，Linux 里的文件不受影响。", ok = "移除")) {
                wb.deleteProject(p.id); if (projectId == p.id) { projectId = null; currentId = null }
            }
        }
        if (drawer.visible) openDrawer()
    }

    private suspend fun conversationMenu(c: CodexConversation) {
        val archiveLabel = if (c.archived) "取消归档" else "归档"
        when (Dialogs.choose(c.title, null, listOf("重命名", archiveLabel, "删除"), highlight = -1)) {
            0 -> Dialogs.input("重命名对话", initial = c.title)?.trim()?.takeIf { it.isNotEmpty() }?.let { wb.renameConversation(c.id, it) }
            1 -> { wb.setArchived(c.id, !c.archived); if (currentId == c.id && !c.archived) currentId = null }
            2 -> if (Dialogs.confirm("删除对话", "删除「${c.title}」的历史记录，项目文件不受影响。", ok = "删除")) {
                wb.deleteConversation(c.id); if (currentId == c.id) currentId = null
            }
        }
        if (drawer.visible) openDrawer()
    }

    // endregion

    // region 审阅面板（文件 / 变更 / 日志）

    private var reviewTab = 0
    private var reviewOpen = false

    /** 查看变更：审阅面板替换会话区域（顶栏不变），再点一次或点关闭回到会话。 */
    private fun openReview() {
        reviewOpen = !reviewOpen
        shownKey = "-"
    }

    private fun buildReview() {
        val panel = CodexReviewPanel(conversation, project, host, reviewTab, { reviewTab = it }, { reviewOpen = false; shownKey = "-" }, { dir, action ->
            val p = project ?: return@CodexReviewPanel Unit
            scope.launch(ArcDispatcher) {
                when (action) {
                    "upload" -> if (host.importFile(p, dir)) shownKey = "-"
                    "new" -> Dialogs.input("新建文件", "文件名", "")?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                        val rel = if (dir.isEmpty()) name else "$dir/$name"
                        if (host.createFile(p, rel)) { shownKey = "-"; host.openFile(p, rel) }
                    }
                }
            }
        }) { name, body -> scope.launch(ArcDispatcher) { host.export(name, body, "text/plain") } }
        content.add(panel).grow()
    }

    // endregion

    private fun exportMarkdown(c: CodexConversation) = buildString {
        append("# ").append(c.title).append("\n\n")
        c.turns.forEach { t ->
            t.items.forEach { i ->
                when (i.type) {
                    "userMessage" -> if ("user" !in t.deletedRoles) append("## 你\n\n").append(i.text).append("\n\n")
                    "agentMessage" -> if ("assistant" !in t.deletedRoles) append("## Codex\n\n").append(i.text).append("\n\n")
                    "commandExecution" -> append("```\n$ ").append(i.command).append("\n").append(i.output.take(4000)).append("\n```\n\n")
                    "fileChange" -> i.changes.forEach { ch -> append("```diff\n# ").append(ch.path).append("\n").append(ch.diff).append("\n```\n\n") }
                }
            }
        }
    }

    private fun exportJson(c: CodexConversation): String {
        val json = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }
        return json.encodeToString(CodexConversation.serializer(), c)
    }

    companion object {
        /** `bash -lc 'xxx'` 只显示 xxx。 */
        fun shortCommand(command: String): String = command.trim()

    }
}

/** 运行中的 10px 圆点（animate-pulse）。 */
internal class Pulse : arc.scene.Element() {
    override fun draw() {
        val a = 0.5f + 0.5f * kotlin.math.abs(kotlin.math.sin(Time.globalTime / 60f * Math.PI.toFloat()))
        arc.graphics.g2d.Draw.color(Css.text.r, Css.text.g, Css.text.b, a * parentAlpha)
        arc.graphics.g2d.Fill.poly(x + width / 2, y + height / 2, 40, width / 2)
        arc.graphics.g2d.Draw.reset()
    }
}
