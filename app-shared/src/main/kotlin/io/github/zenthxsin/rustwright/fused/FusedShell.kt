package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import java.io.File
import arc.Core
import arc.scene.Element
import arc.scene.ui.layout.Reveal
import arc.scene.ui.layout.Stack
import arc.scene.ui.layout.Switcher
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.codex.CodexConversation
import io.github.zenthxsin.rustwright.core.codex.CodexItem
import io.github.zenthxsin.rustwright.core.codex.CodexMode
import io.github.zenthxsin.rustwright.core.codex.CodexProject
import io.github.zenthxsin.rustwright.core.codex.CodexTurn
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import io.github.zenthxsin.rustwright.ui.designsystem.BackHandler
import io.github.zenthxsin.rustwright.ui.designsystem.BackHistory
import io.github.zenthxsin.rustwright.ui.designsystem.BackNavigation
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.Banner
import io.github.zenthxsin.rustwright.ui.kit.DrawerHost
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.rwfade
import io.github.zenthxsin.rustwright.ui.kit.SheetHost
import io.github.zenthxsin.rustwright.ui.kit.iconButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 融合版外壳（原型 App.dc.html）：一个助手，全部能力。
 * 顶栏 + 页面（首页 / 对话 / 产物 / 项目 / 设置 / 更多 / 工具）+ 输入框，外加侧边栏、底部弹层、顶部通知。
 */
class FusedShell(val host: FusedHost) : Stack() {
    enum class Page(val bigTitle: Boolean = false) { HOME, CHAT, LIBRARY, PROJECTS, SETTINGS(true), MORE(true), TOOL }

    private val wb get() = host.wb
    var page = Page.HOME
        private set
    private var convId: String? = null
    private var toolTitle = ""
    private var toolDesc = ""
    private var toolElement: Element? = null
    private var toolBack = Page.MORE
    private data class Destination(val page: Page, val conversationId: String? = null,
        val title: String = "", val description: String = "", val content: Element? = null)
    private val history = BackHistory(Destination(Page.HOME))
    private var navigationRevision = 0L

    // 新对话的默认设置（发出第一条消息时用）
    private var pendingMode = CodexMode.AUTO
    private var pendingModel: String? = null
    private var pendingEffort: String? = null

    val sheets = SheetHost()
    val banner = Banner()
    val menu = MenuHost()
    // 原型：push / pop 横移 48px，同级切换上浮 10px + 0.985 缩放，0.34s cubic-bezier(.2,.8,.2,1)
    private val switcher = Switcher().apply { slide = dp(48f); rise = dp(10f) }
    private val drawer: DrawerHost
    private val slash by lazy { SlashController(this) }
    private val composer: Composer
    private val composerReveal: Reveal
    private val header = ShellHeader(::headerState, ::headerAction, ::openDrawer, ::requestBack, ::openHeaderTitle) { Sheets.usage(this, context = true) }
    private var headerCell: arc.scene.ui.layout.Cell<ShellHeader>? = null
    private var headerHidden = false
    // ---- 大屏自适应（平板、电脑、横屏） ----
    /** 左侧常驻侧栏 + 右侧内容；窄屏时只有内容，侧栏是抽屉。 */
    private val split = Table()
    private val dock = Table(K.fill(K.drawerBg, 0f))
    private var switcherCell: arc.scene.ui.layout.Cell<Switcher>? = null
    private var composerCell: arc.scene.ui.layout.Cell<*>? = null
    /** 当前是否宽屏（侧栏常驻）。 */
    var wide = false
        private set
    /** 宽屏时侧栏是否展开（左上角按钮切换）。 */
    private var dockShown = true
    private var contentMax = -1f
    private lateinit var column: Table
    private val pages = HashMap<Page, Element>()
    private val chat: ChatView

    init {
        chat = ChatView(wb, { conversation() }, object : ChatView.ChatActions {
            override fun openSteps(turn: CodexTurn, items: List<CodexItem>) { Sheets.steps(this@FusedShell, items) }
            override fun copy(text: String) { host.copy(text); notify("已复制") }
            override fun attachmentFile(path: String) = host.ui?.projectDir()?.let { java.io.File(it, path) }?.takeIf { it.isFile }
            override fun popup(items: List<MenuHost.Item>, anchor: Element) = menu.show(items, anchor)
            override fun regenerate(turn: CodexTurn) { this@FusedShell.regenerate(turn) }
            override fun produced(turn: CodexTurn) = host.produced(turn)
            // 对话底部「产物 · N」：直接进这个对话所在项目的产物
            override fun openLibrary() { libraryScope = conversation()?.projectId ?: currentProject()?.id ?: LIB_HOME; libraryKind = null; libraryQuery = ""; show(Page.LIBRARY) }
            override fun openArtifact(a: Artifact) { this@FusedShell.openArtifact(a) }
            private val checks = HashMap<String, TurnCheck?>()
            private val checking = HashSet<String>()
            override fun turnCheck(turn: CodexTurn, ready: () -> Unit): TurnCheck? {
                val key = turn.id + "@" + turn.completedAt
                if (checks.containsKey(key)) return checks[key]
                if (checking.add(key)) host.scope.launch {
                    val r = runCatching { host.checkTurn(turn) }.getOrNull()
                    arc.Core.app.post { checks[key] = r; checking.remove(key); if (r != null) { ready(); autoFix(turn, key, r) } }
                }
                return null
            }
            private val autoFixed = HashSet<String>()

            /**
             * 检查出 error 就直接让助手接着修（用户选的全自动）。只对当前对话刚正常结束的最后一轮生效
             * （翻旧对话、用户点了停止都不触发），每轮只触发一次，连续自动修 [TurnCheck.MAX_AUTO_ROUNDS] 轮还有错误就停下。
             */
            private fun autoFix(turn: CodexTurn, key: String, r: TurnCheck) {
                if (r.errors == 0 || !autoFixed.add(key)) return
                val c = conversation() ?: return
                if (c.turns.lastOrNull() !== turn || c.status.active || turn.status != "completed") return
                if (System.currentTimeMillis() - (turn.completedAt ?: 0L) > 120_000) return
                val rounds = c.turns.reversed().takeWhile { t ->
                    t.items.firstOrNull { it.type == "userMessage" }?.text?.trimStart()?.startsWith(TurnCheck.AUTO_PREFIX) == true
                }.size
                if (rounds >= TurnCheck.MAX_AUTO_ROUNDS) {
                    notify("自动修了 $rounds 轮还有 ${r.errors} 个错误，先停下，点「检查结果」看看")
                    return
                }
                wb.send(c.id, r.fixRequest(errorsOnly = true))
            }

            override fun openCheck(check: TurnCheck) { Sheets.check(this@FusedShell, check) }
            override fun openLink(url: String) {
                if (url.startsWith("http://") || url.startsWith("https://")) { arc.Core.app.openURI(url); return }
                // 模型常给 sandbox:/mnt/data/x.tmx、/workspace/... 这类文件链接：先按路径找，找不到再按文件名在项目里找
                val name = url.substringAfterLast('/').substringBefore('?')
                val f = host.resolveFile(url)
                if (f != null) { openFile(f); return }
                val a = host.library().firstOrNull { it.name == name }
                if (a != null) openArtifact(a) else notify("找不到「$name」，可能已经被移走或删掉了")
            }
        })
        composer = Composer(
            running = { conversation()?.status?.active == true },
            readingBack = { page == Page.CHAT && chat.readingBack },
            inChat = { page == Page.CHAT },
            modelLabel = { modelLabel() },
            onSend = { send(it) },
            onSteer = { text -> conversation()?.let { wb.send(it.id, text, steer = true); true } ?: false },
            onStop = { conversation()?.let { wb.interrupt(it.id) } },
            onAttach = { Sheets.attach(this) },
            onModel = { Sheets.model(this) },
            onCommand = { slash.execute(it) },
        )
        composerReveal = Reveal(Table().apply { add(composer).growX().pad(0f, dp(12f), dp(16f), dp(12f)) }).axis(Reveal.VERTICAL)
        composerReveal.collapsedScale = 1f

        column = Table(K.fill(K.bg, 0f))
        headerCell = column.add(header).growX().height(dp(64f))
        column.row()
        switcherCell = column.add(switcher).grow()
        column.row()
        composerCell = column.add(composerReveal).growX()
        split.add(column).grow()
        add(split)
        drawer = DrawerHost(DrawerPanel(this))
        add(menu)
        add(drawer)
        add(sheets)
        host.setup()?.takeIf { !it.ready }?.let { st ->
            setupWatch = st
            add(SetupView(st, permissions = { host.permissions() }, openPermission = { host.openPermission(it) }, onFailure = { report ->
                host.copy(report)
                openToolId("terminal")
                notify("报错日志已复制")
            }) { background ->
                column.rwfade(0.45f)
                if (!background) notify("虚拟环境已就绪，${AppBrand.identity.assistant}可以开始干活了") else setupPending = true
            })
        }
        add(banner)
        io.github.zenthxsin.rustwright.ui.widgets.Toasts.handler = { banner.show(it) }
        show(Page.HOME, Switcher.NONE)
    }

    private var setupWatch: SetupState? = null

    /** 外壳顶栏高度：工具页自带顶栏时收起；项目 / 产物 / 更多 / 设置多一行大标题（参考 Claude App 的一级页）。 */
    private var headerH = -1f

    private fun syncHeader() {
        val own = page == Page.TOOL && toolElement is io.github.zenthxsin.rustwright.ui.kit.OwnHeader
        val h = when {
            own -> 0f
            page.bigTitle -> dp(64f + BIG_TITLE_H)
            else -> dp(64f)
        }
        if (h == headerH) return
        headerH = h
        headerHidden = own
        header.visible = !own
        // 收起时把顶栏从格子里拿掉：Arc 里 maxHeight = 0 表示「不限」，只设 height(0) 时格子会按顶栏自身的
        // 首选高度（含大标题一行）撑开，二级页上方就空出一大块
        headerCell?.let { c ->
            if (own) c.setElement(null) else if (c.get() == null) c.setElement(header)
            c.height(h)
        }
        column.invalidateHierarchy()
    }

    private var setupPending = false

    /**
     * 预热：启动后空闲时把几个一级页各离屏建一次、排一次版，提前生成字形和图标纹理。
     * 不预热的话第一次点进去要卡 0.2–0.4 秒（桌面上测的，手机更久），入场动画看起来就像没有。
     */
    private val warmup = ArrayDeque(listOf(Page.MORE, Page.SETTINGS, Page.PROJECTS, Page.LIBRARY))
    /**
     * 弹层预热（桌面上测：模型 228ms、用量 367ms、添加上下文 88ms，第二次都在 2ms 内）。
     * 一帧一个，排在页面预热之后；用户正在用弹层或侧边栏时跳过这一帧。
     */
    private val sheetWarmup = ArrayDeque<() -> Unit>(listOf(
        { drawer.prewarm() },
        { sheets.prewarm { Sheets.model(this) } },
        { sheets.prewarm { Sheets.usage(this) } },
        { sheets.prewarm { Sheets.attach(this) } },
        { sheets.prewarm { Sheets.modeSheet(this) } },
    ))
    private var warmupDelay = 1.2f

    /**
     * 启动动画期间要做的预加载：一级页、侧边栏、常用弹层、「更多」里每个工具页。
     * 工具页大多会被缓存（终端、地图等），之后打开直接用；每次新建的页面也借这一遍生成好字形和图标。
     */
    fun splashTasks(): List<Pair<String, () -> Unit>> {
        val list = ArrayList<Pair<String, () -> Unit>>()
        // 先拉起助手 / Codex（后台异步），和下面的界面预加载同时进行
        list += "${AppBrand.identity.assistant}" to { runCatching { host.preloadAgent() }.onFailure { arc.util.Log.err("[FusedShell] preload agent", it) }; Unit }
        list += "对话渲染" to {
            runCatching {
                val md = io.github.zenthxsin.rustwright.ui.widgets.MarkdownView(ChatView.mdStyle)
                md.setMarkdown(MD_SAMPLE)
                md.setSize(switcher.width.coerceAtLeast(1f) - dp(32f), 10f)
                md.validate()
                md.prefHeight
            }.onFailure { arc.util.Log.err("[FusedShell] preload markdown", it) }
            Unit
        }
        while (warmup.isNotEmpty()) { val p = warmup.removeFirst(); list += "页面" to { warmPage(p) } }
        while (sheetWarmup.isNotEmpty()) { val w = sheetWarmup.removeFirst(); list += "弹窗" to { runCatching { w() }.onFailure { arc.util.Log.err("[FusedShell] sheet warmup", it) }; Unit } }
        for (g in runCatching { host.tools() }.getOrDefault(emptyList())) for (t in g.tools) list += t.name to {
            runCatching {
                t.open?.invoke()?.let { el ->
                    el.setSize(switcher.width.coerceAtLeast(1f), switcher.height.coerceAtLeast(1f))
                    (el as? arc.scene.Group)?.validate()
                }
            }.onFailure { arc.util.Log.err("[FusedShell] preload tool ${t.id}", it) }
            Unit
        }
        return list
    }

    /** 启动动画到时间还没做完的预加载，留到空闲时一帧一个慢慢做。 */
    private val lateWarmup = ArrayDeque<() -> Unit>()

    /** 启动动画：盖在最上面，跑完预加载后淡出，主页内容再做一次入场。 */
    fun startSplash() {
        if (width <= 0f) validate()
        val splash = SplashView(splashTasks(), onLeftover = { rest -> lateWarmup.addAll(rest) }) { switcher.replay(Switcher.FADE, 0.45f) }
        addChild(splash)
        splash.toFront()
    }

    private fun warmupStep(delta: Float) {
        if (warmup.isEmpty() && sheetWarmup.isEmpty() && lateWarmup.isEmpty() || switcher.isAnimating || drawer.isOpen || sheets.isOpen) return
        warmupDelay -= delta
        if (warmupDelay > 0f) return
        warmupDelay = 0.25f
        if (warmup.isEmpty() && sheetWarmup.isEmpty()) {
            lateWarmup.removeFirst()()
            return
        }
        if (warmup.isEmpty()) {
            val w = sheetWarmup.removeFirst()
            runCatching { w() }.onFailure { arc.util.Log.err("[FusedShell] sheet warmup", it) }
            return
        }
        warmPage(warmup.removeFirst())
    }

    /** 离屏建一次一级页并按真实尺寸排版（「更多」直接缓存下来）。 */
    private fun warmPage(p: Page) {
        if (p == page) return
        runCatching {
            val el = when (p) {
                Page.MORE -> pages.getOrPut(p) { MorePage(this) }
                Page.SETTINGS -> SettingsPage(this)
                Page.PROJECTS -> ProjectsPage(this)
                Page.LIBRARY -> LibraryPage(this)
                else -> return
            }
            el.setSize(switcher.width.coerceAtLeast(1f), switcher.height.coerceAtLeast(1f))
            if (el is arc.scene.Group) el.validate()
        }.onFailure { arc.util.Log.err("[FusedShell] warmup $p", it) }
    }

    override fun act(delta: Float) {
        syncWide()
        syncHeader()
        super.act(delta)
        warmupStep(delta)
        // 「后台继续」后装好了再提醒一次
        if (setupPending && setupWatch?.ready == true) {
            setupPending = false
            notify("虚拟环境已就绪，${AppBrand.identity.assistant}可以开始干活了")
        }
    }

    // ---- 状态 ----

    fun conversation(): CodexConversation? = convId?.let { wb.conversation(it) }

    /** 产物页的筛选：范围（全部项目 / 本会话 / 某个项目 id）、类型、搜索词；换页回来还在。 */
    var libraryScope: String = LIB_HOME
    var libraryKind: Artifact.Kind? = null
    var libraryQuery = ""
    internal var libraryView: LibraryPage? = null

    /** 产物页右上角：按类型筛选。 */
    fun libraryFilter(anchor: Element) {
        val kinds = listOf<Pair<Artifact.Kind?, String>>(null to "所有类型") + host.fileKinds().map { it to it.label }
        menu.show(kinds.map { (k, label) -> MenuHost.Item(label, LibraryPage.kindIcon(k), checked = libraryKind == k) { libraryKind = k; libraryView?.rebuild() } }, anchor)
    }

    /** 产物页当前看的范围名（顶栏中间显示）。 */
    fun libraryScopeName(): String = when (libraryScope) {
        LIB_HOME -> "产物"
        else -> host.wb.projects.firstOrNull { it.id == libraryScope }?.name ?: "产物"
    }

    /** 当前对话做出来的产物（去重）。 */
    fun conversationArtifacts(): List<Artifact> =
        conversation()?.turns.orEmpty().filter { it.status != "inProgress" }.flatMap { host.produced(it) }.distinctBy { it.path ?: it.id }

    fun currentProject(): CodexProject? =
        host.currentProjectId()?.let { wb.project(it) } ?: wb.projects.firstOrNull { !it.archived }

    val mode: CodexMode get() = conversation()?.mode ?: pendingMode
    val model: String get() = conversation()?.model ?: pendingModel ?: wb.defaultModel()
    val effort: String get() = conversation()?.effort ?: pendingEffort ?: wb.models.firstOrNull { it.id == model }?.defaultEffort ?: "medium"

    fun setMode(m: CodexMode) {
        conversation()?.let { wb.setMode(it.id, m) } ?: run { pendingMode = m }
    }

    fun setModel(id: String) {
        conversation()?.let { if (!wb.setModel(it.id, id, effort)) notify("请等待当前任务结束后切换模型") } ?: run { pendingModel = id }
    }

    fun setEffort(e: String) {
        conversation()?.let { if (!wb.setModel(it.id, model, e)) notify("请等待当前任务结束后调整") } ?: run { pendingEffort = e }
    }

    private fun modelLabel(): String = Labels.model(model, wb) + "|" + Labels.effort(effort)

    // ---- 导航 ----

    fun notify(text: String) = banner.show(text)
    fun notify(text: String, seconds: Float, tap: () -> Unit) = banner.show(text, seconds, tap)

    /** 窄屏拉出抽屉；宽屏收起 / 展开常驻侧栏。 */
    fun openDrawer() {
        if (!wide) return drawer.open()
        dockShown = !dockShown
        rebuildSplit()
    }

    fun closeDrawer() { if (!wide) drawer.close() }

    /** 按宽度切换布局：≥ [WIDE_DOCK_DP] dp 侧栏常驻；转屏、拖窗口大小时实时切换。 */
    private fun syncWide() {
        if (width <= 0f) return
        val w = width >= dp(WIDE_DOCK_DP)
        if (w != wide) {
            wide = w
            if (wide) {
                val content = drawer.lend()
                dock.clearChildren()
                dock.add(content).grow()
            } else {
                dock.clearChildren()
                drawer.reclaim()
            }
            rebuildSplit()
        }
        // 内容限宽：≥ 600dp 时聊天、首页、列表页最宽 [CONTENT_MAX_DP] 居中；工具页（画布、编辑器、终端）铺满；
        // 「更多」是自动分列的网格（AutoGrid），宽屏上铺满才能多排几列
        val avail = width - if (wide && dockShown) dp(DOCK_DP) else 0f
        val max = if (avail >= dp(600f) && page != Page.TOOL && page != Page.MORE) dp(CONTENT_MAX_DP) else 0f
        if (max != contentMax) {
            contentMax = max
            headerCell?.maxWidth(max)
            switcherCell?.maxWidth(max)
            composerCell?.maxWidth(max)
            column.invalidateHierarchy()
        }
    }

    private fun rebuildSplit() {
        split.clearChildren()
        if (wide && dockShown) {
            split.add(dock).width(dp(DOCK_DP)).growY()
            split.add(Table(K.fill(K.line, 0f))).width(dp(1f)).growY()
        }
        split.add(column).grow()
        contentMax = -1f
        split.invalidateHierarchy()
    }

    fun show(p: Page, mode: Int = Switcher.FADE) {
        if (p == Page.TOOL) return
        navigationRevision++
        history.visit(Destination(p, if (p == Page.CHAT) convId else null))
        Core.scene?.setKeyboardFocus(null)
        page = p
        val t0 = System.nanoTime()
        val el = when (p) {
            Page.HOME -> pages.getOrPut(p) { homePage() }
            Page.CHAT -> pages.getOrPut(p) { chat }
            Page.LIBRARY -> LibraryPage(this)
            Page.PROJECTS -> ProjectsPage(this)
            Page.SETTINGS -> SettingsPage(this)
            Page.MORE -> pages.getOrPut(p) { MorePage(this) }
            Page.TOOL -> return
        }
        if (NAV_PERF) { el.setSize(switcher.width, switcher.height); (el as? arc.scene.Group)?.validate(); arc.util.Log.info("[navperf] $p ${(System.nanoTime() - t0) / 1_000_000}ms") }
        switcher.show(el, mode)
        // 原型里输入框随页面直接出现 / 消失（sc-if），没有过渡
        composerReveal.setShown((p == Page.HOME && !toolFirst) || p == Page.CHAT, false)
        drawer.close()
        menu.hide()
        sheets.close()
    }

    /** 贴着 [anchor] 弹出浮层菜单（长按消息、长按项目）。 */
    fun popup(items: List<MenuHost.Item>, anchor: Element) = menu.show(items, anchor)

    fun openChat(id: String) {
        // 会话属于某个项目：切过去时当前项目也跟着换，文件管理、编辑器、产物看的都是这个会话的项目
        wb.conversation(id)?.projectId?.let { pid ->
            if (pid != host.currentProjectId() && wb.project(pid)?.archived == false) host.setCurrentProject(pid)
        }
        convId = id
        show(Page.CHAT)
    }

    fun newChat() {
        convId = null
        show(Page.HOME)
    }

    /** Open artifacts through their registered file handlers. */
    fun openArtifact(a: Artifact) {
        val f = a.path?.let(::File)?.takeIf { it.isFile }
        if (f != null) return openFile(f)
        host.openArtifact(a)?.let { openTool(a.name, it) } ?: notify("「${a.name}」暂时打不开")
    }

    fun artifactMenu(a: Artifact, anchor: Element) {
        menu.show(listOf(
            MenuHost.Item("导出", PI.up) {
                launch {
                    try {
                        val file = host.artifactFile(a) ?: return@launch notify("找不到「${a.name}」，文件可能已被移走或删除。")
                        host.saveFile(file)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        notify("导出失败：${e.message}")
                    }
                }
            },
            MenuHost.Item("删除", PI.trash, K.danger) {
                launch {
                    if (!io.github.zenthxsin.rustwright.ui.widgets.Dialogs.confirm("删除产物", "确定删除「${a.name}」？项目中的这个文件也会被删除，无法撤销。", ok = "删除")) return@launch
                    try {
                        if (host.deleteArtifact(a)) { libraryView?.refreshFiles(); notify("已删除 ${a.name}") }
                        else notify("删除失败，文件可能已被移走或删除。")
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        notify("删除失败：${e.message}")
                    }
                }
            },
        ), anchor)
    }

    /** Plugins get first refusal; standard images and text remain usable without them. */
    fun openFile(f: File) {
        if (host.openRegistered(f)) return
        host.filePresentation(f)?.open?.invoke(f)?.let { openTool(f.name, it, desc = f.path); return }
        if (f.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")) StepPreview.open(this, f)
        else if (io.github.zenthxsin.rustwright.ui.editor.TextFiles.isText(f)) {
            sheets.close(); openTool(f.name, TextEditorPage(this, f), desc = f.path)
        } else launch { host.saveFile(f) }
    }

    /** 打开一个工具页（从右侧滑入，左上角返回）。 */
    fun openTool(title: String, content: Element, back: Page = page.takeIf { it != Page.TOOL } ?: Page.MORE, desc: String = "") {
        navigationRevision++
        history.visit(Destination(Page.TOOL, title = title, description = desc, content = content))
        toolTitle = title
        toolDesc = desc
        toolElement = content
        (content as? io.github.zenthxsin.rustwright.ui.kit.OwnHeader)?.bindBack { requestBack() }
        rightKeyReset()
        toolBack = back
        page = Page.TOOL
        Core.scene?.setKeyboardFocus(null)
        switcher.show(content, Switcher.PUSH)
        composerReveal.setShown(false, false)
        menu.hide()
        drawer.close()
        sheets.close()
    }

    /** All back affordances use the same ordering, never exiting from a child page. */
    fun requestBack() {
        Core.scene?.let { if (BackNavigation.dismissOverlay(it)) return }
        children.toList().asReversed().filter { it.visible }.forEach {
            if (it is BackHandler && it.onBackRequested {}) return
        }
        if (sheets.isOpen) { sheets.pop(); return }
        if (drawer.isOpen) { drawer.close(); return }
        if (menu.isShown) { menu.hide(); return }
        if (page == Page.CHAT && chat.clearSelection()) return
        // 产物页在某个项目里：先回到项目列表
        if (page == Page.LIBRARY && libraryScope != LIB_HOME) { libraryScope = LIB_HOME; libraryKind = null; libraryQuery = ""; libraryView?.rebuild(); return }
        val revision = navigationRevision
        var left = false
        val leave = {
            if (!left && navigationRevision == revision) {
                left = true
                backPage()
            }
        }
        if (page == Page.TOOL && (toolElement as? BackHandler)?.onBackRequested(leave) == true) return
        leave()
    }

    private fun backPage() {
        var destination = history.back()
        while (destination?.page == Page.CHAT && wb.conversation(destination.conversationId.orEmpty())?.let { !it.archived } != true) {
            destination = history.back()
        }
        if (destination == null) { notify("已在首页"); return }
        navigationRevision++
        Core.scene?.setKeyboardFocus(null)
        page = destination.page
        convId = destination.conversationId
        rightKeyReset()
        val element = if (page == Page.TOOL) {
            toolTitle = destination.title
            toolDesc = destination.description
            toolElement = destination.content
            (destination.content as? io.github.zenthxsin.rustwright.ui.kit.OwnHeader)?.bindBack { requestBack() }
            checkNotNull(destination.content)
        } else when (page) {
            Page.HOME -> pages.getOrPut(page) { homePage() }
            Page.CHAT -> chat
            Page.LIBRARY -> LibraryPage(this)
            Page.PROJECTS -> ProjectsPage(this)
            Page.SETTINGS -> SettingsPage(this)
            Page.MORE -> pages.getOrPut(page) { MorePage(this) }
            Page.TOOL -> error("unreachable")
        }
        switcher.show(element, Switcher.POP)
        composerReveal.setShown((page == Page.HOME && !toolFirst) || page == Page.CHAT, false)
    }

    // ---- 发送 ----

    /** 选图 / 拍照 / 选文件 → 复制进当前项目的 tmp/ → 放进输入框上方的附件条，发送时把路径交给助手。 */
    internal fun attachFrom(kind: String) {
        val dir = host.ui?.projectDir()?.takeIf { it.isDirectory } ?: run {
            notify("先打开一个项目，附件会放进项目的 tmp/")
            return
        }
        val originalProject = currentProject()?.id
        val originalConversation = convId
        launch {
            val picked = try { host.pickAttachments(kind) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { notify("选择文件失败：${e.message}"); return@launch }
            if (picked.isEmpty()) return@launch
            for (file in picked) {
                if (currentProject()?.id != originalProject || convId != originalConversation) {
                    notify("会话已切换，请在当前会话重新选择附件")
                    return@launch
                }
                val rel = try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Attachments.copyIn(dir, file) } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { notify("附件保存失败：${file.name}：${e.message}"); continue }
                if (currentProject()?.id != originalProject || convId != originalConversation) return@launch
                composer.addAttachment(java.io.File(dir, rel), rel)
            }
        }
    }

    internal fun ensureCommandConversation(): CodexConversation? {
        if (page == Page.CHAT) conversation()?.let { return it }
        host.blocker()?.let { notify(it); return null }
        val project = currentProject() ?: run { notify("请先选择项目"); show(Page.PROJECTS); return null }
        val c = wb.newConversation(project.id, pendingMode, pendingModel ?: wb.defaultModel(), pendingEffort)
        convId = c.id; show(Page.CHAT)
        return c
    }

    internal fun fillCommandDraft(text: String) {
        show(if (conversation() == null) Page.HOME else Page.CHAT)
        composer.input.setText(text)
        composer.input.setCursorPosition(text.length)
        Core.scene?.setKeyboardFocus(composer.input)
    }

    internal fun send(text: String): Boolean {
        if (SlashCommands.isCommand(text)) return slash.execute(text)
        host.blocker()?.let { notify(it); return false }
        val c = conversation()
        if (c != null && page == Page.CHAT) {
            wb.send(c.id, text)
            return true
        }
        val project = currentProject() ?: run {
            notify("先新建一个项目，${AppBrand.identity.assistant}在项目里干活")
            show(Page.PROJECTS)
            return false
        }
        val conv = wb.newConversation(project.id, pendingMode, pendingModel ?: wb.defaultModel(), pendingEffort)
        convId = conv.id
        show(Page.CHAT)
        wb.send(conv.id, text)
        // 标题先用本地首句；免费会话始终保留本地标题。
        wb.renameConversation(conv.id, quickTitle(Attachments.split(text).first.ifBlank { "附件" }))
        val titleModel = conv.model
        launch {
            conversationTitle(titleModel, text, host::makeTitle)?.let { t -> arc.Core.app.post { if (wb.conversation(conv.id) != null) wb.renameConversation(conv.id, t) } }
        }
        return true
    }

    /** 第一句话去掉标点后的前 10 个字。 */
    private fun quickTitle(text: String): String =
        text.trim().split(Regex("[，。！？；,.!?;\\n]")).firstOrNull { it.isNotBlank() }?.replace(Regex("\\s+"), "")?.take(10)?.ifBlank { null } ?: "新对话"

    /** 重新生成：去掉上一次回复，按同一条消息重新跑。 */
    private fun regenerate(turn: CodexTurn) {
        val c = conversation() ?: return
        if (c.status.active) return
        val text = turn.items.firstOrNull { it.type == "userMessage" }?.text ?: return
        wb.deleteMessage(c.id, turn.id, "assistant")
        wb.deleteMessage(c.id, turn.id, "user")
        wb.send(c.id, text)
    }

    /** 打开「更多」里的工具：有界面就打开，没有就显示占位页。 */
    fun openToolEntry(t: ToolEntry, g: ToolGroup, back: Page = Page.MORE) {
        val e = t.open?.invoke() ?: GenericToolPage(this, t, g)
        openTool(t.name, e, back, t.desc)
    }

    /** 按 id 打开工具页；没有这个工具返回 false。 */
    fun openToolId(id: String, back: Page = page.takeIf { it != Page.TOOL } ?: Page.MORE): Boolean {
        val (t, g) = host.tool(id) ?: return false
        openToolEntry(t, g, back)
        return true
    }

    /** 输入框里现在的草稿（自查脚本核对用）。 */
    fun draft(): String = composer.input.text

    /** 把话填进输入框，光标放在末尾（不发送）。 */
    fun setDraft(text: String) {
        composer.input.setText(text)
        composer.input.setCursorPosition(text.length)
    }

    /** 回到首页并把话填进输入框（「让助手代劳」）。 */
    fun askRust(text: String) {
        newChat()
        composer.input.setText(text)
        composer.input.setCursorPosition(text.length)
        arc.Core.scene?.setKeyboardFocus(composer.input)
    }

    private fun rightKeyReset() { header.rightKey = "" }

    fun projectName(): String = currentProject()?.name ?: "未选择项目"

    fun launch(block: suspend CoroutineScope.() -> Unit) = host.scope.launch(ArcDispatcher, block = block)

    // ---- 顶栏 ----

    private fun headerState(): ShellHeader.State {
        val c = conversation()
        val (t, s, color) = when (page) {
            Page.HOME -> Triple(projectName(), "", K.muted)
            Page.CHAT -> {
                val name = c?.title?.takeIf { it.isNotBlank() && it != "新会话" }
                Triple(name ?: "新对话", projectName() + " · " + Labels.mode(mode) + (if (c?.planMode == true) " · 计划" else ""), if (name != null) K.text else K.subtle)
            }
            Page.TOOL -> Triple(toolTitle, "", K.text)
            // 项目页：小标题放在顶栏中间
            Page.PROJECTS -> Triple("项目", "", K.text)
            // 产物页：首页是「产物」，进了项目是「项目名 / 产物」
            Page.LIBRARY -> Triple(if (libraryScope == LIB_HOME) "产物" else libraryScopeName(), "", K.text)
            // 一级页：顶栏中间留空，标题放下面一行大字
            else -> Triple("", "", K.text)
        }
        val usage = c?.let { (it.contextTokens.toFloat() / (if (it.contextWindow > 0) it.contextWindow else 400_000L)).coerceIn(0f, 1f) } ?: 0f
        val nested = page == Page.LIBRARY && libraryScope != LIB_HOME
        return ShellHeader.State(page, t, s, color, page == Page.TOOL || nested,
            page.name + if (nested) "|in" else "", usage)
    }

    private fun openHeaderTitle() {
        when (page) {
            Page.HOME -> show(Page.PROJECTS)
            Page.CHAT -> if (conversation() != null) Sheets.chatMenu(this)
            else -> {}
        }
    }

    private fun headerAction(): Element? = when (page) {
        Page.CHAT -> iconButton(PI.dots, 22f, stroke = 1.6f) { Sheets.chatMenu(this) }.apply { name = "对话菜单" }
        Page.PROJECTS -> iconButton(PI.down, 22f, stroke = 1.6f) {
            launch { host.importProject()?.let { host.setCurrentProject(it.id); show(Page.PROJECTS, Switcher.NONE) } }
        }.apply { name = "导入" }
        // 产物首页（项目列表）不放筛选，进了项目才按类型筛
        Page.LIBRARY -> if (libraryScope == LIB_HOME) null else {
            lateinit var b: Element
            b = iconButton(PI.sliders, 22f, stroke = 1.6f) { libraryFilter(b) }.apply { name = "筛选" }
            b
        }
        Page.MORE -> iconButton(PI.search, 22f, stroke = 1.6f) { notify("搜索还没做") }.apply { name = "搜索" }
        Page.SETTINGS -> iconButton(PI.info, 22f, stroke = 1.6f) { launch { AboutWorkbench.show() } }.apply { name = "关于" }
        Page.TOOL -> iconButton(PI.info, 22f, stroke = 1.6f) {
            // 工具页自己接管（终端：虚拟机状态），否则显示说明
            (toolElement as? io.github.zenthxsin.rustwright.ui.kit.HeaderAction)?.onHeaderAction() ?: notify(toolDesc.ifBlank { toolTitle })
        }.apply { name = (toolElement as? io.github.zenthxsin.rustwright.ui.kit.HeaderAction)?.headerLabel ?: "说明" }
        else -> null
    }


    // ---- 首页 ----

    /** 「工具为主」版：首页换成项目 + 工具 + 最近编辑，助手收成底部一条（设置里切换）。 */
    var toolFirst = false
        set(v) {
            if (field == v) return
            field = v
            onToolFirstChanged?.invoke(v)
            pages.remove(Page.HOME)
            if (page == Page.HOME) show(Page.HOME, Switcher.NONE)
        }

    /** 首页样式变了（设置里或开发者页里切换）：外面负责记住选择。 */
    var onToolFirstChanged: ((Boolean) -> Unit)? = null

    private fun homePage(): Element = if (toolFirst) host.workbenchHome(this) ?: MorePage(this) else WorkbenchHomePage(host, host, ::currentProject,
        { text -> setDraft(text); Core.scene?.setKeyboardFocus(composer.input) },
        { action -> launch { action() } })


}

/** 一级页大标题那一行的高度（dp）。 */
internal const val BIG_TITLE_H = 52f

/** -Drw.navperf：打印每次切页建页面 + 排版的耗时。 */
private val NAV_PERF = System.getProperty("rw.navperf") != null

/** 预热对话渲染用的样例：标题、粗体、行内代码、代码块、列表、表格、中英文混排。 */
private const val MD_SAMPLE = """## 铁砧坦克 · 检查结果
已检查 **3 个单位**，修改了 `hp` 和 `moveSpeed`：
- 保留生命值 4200，可以调整移动速度
- 炮塔转速 ➜ 0.8

```ini
[core]
name: anvilTank
maxHp: 4200
```

| 参数 | 当前值 | 建议 |
|---|---|---|
| moveSpeed | 0.6 | 0.75 |
"""

/** 宽度达到这么多 dp 时侧栏常驻（平板横屏、电脑窗口）。 */
private const val WIDE_DOCK_DP = 840f
/** 常驻侧栏宽度。 */
private const val DOCK_DP = 300f
/** 聊天、首页、列表页的最大内容宽度。 */
private const val CONTENT_MAX_DP = 760f

internal const val LIB_HOME = "home"
