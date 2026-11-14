package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.scene.Element
import arc.scene.event.Touchable
import arc.scene.ui.ScrollPane
import arc.scene.ui.TextField
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import arc.scene.ui.layout.Stack
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.listRow
import io.github.zenthxsin.rustwright.ui.kit.pill
import io.github.zenthxsin.rustwright.ui.kit.sectionTitle

/** 可滚动的页面内容。 */
fun scrollPage(content: Table): ScrollPane = ScrollPane(content).apply {
    setScrollingDisabledX(true)
    setOverscroll(false, true)
}

// ---------------- 侧边栏 ----------------

internal class DrawerPanel(private val s: FusedShell) : Stack() {
    private val recent = Table().top().left()
    /** 最近使用的重建键：对话数据、当前页、当前对话、哪些在运行——切对话时数据没变也要重画高亮。 */
    private var shownRecent = ""
    private var shownPage: FusedShell.Page? = null
    private val nav = Table().top()

    init {
        val col = Table().top().left()
        val brand = Table().left()
        brand.add(K.serif(io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.name, 32f)).bottom()
        brand.add(K.mono("v" + Version.name, 13f, K.subtle).apply { setEllipsis(true) }).bottom().minWidth(0f).padLeft(dp(10f)).padBottom(dp(5f))
        col.add(brand).left().minWidth(0f).pad(dp(40f), dp(24f), dp(14f), dp(24f)).row()
        col.add(nav).growX().pad(0f, dp(10f), 0f, dp(10f)).row()
        col.add(Divider()).growX().height(dp(1f)).pad(dp(10f), dp(24f), dp(10f), dp(24f)).row()
        col.add(sectionTitle("最近使用")).left().pad(dp(10f), dp(24f), dp(6f), dp(24f)).row()
        val scroll = ScrollPane(recent).apply { setScrollingDisabledX(true) }
        col.add(scroll).grow().pad(0f, dp(10f), 0f, dp(10f))
        add(col)

        // 底部 110px：上半段从透明渐变到底色，按钮行离底 20（原型 nav 底部）
        val fade = object : Element() {
            override fun draw() {
                val c0 = Color(K.drawerBg).a(0f).toFloatBits()
                val c1 = Color(K.drawerBg).a(parentAlpha).toFloatBits()
                val mid = y + height / 2f
                Fill.quad(x, mid, c1, x + width, mid, c1, x + width, y + height, c0, x, y + height, c0)
                Draw.color(K.drawerBg, parentAlpha)
                Fill.crect(x, y, width, height / 2f)
                Draw.reset()
            }
        }.apply { touchable = Touchable.disabled }
        val bar = Table()
        val avatar = Tap(K.accent, 25f) { s.show(FusedShell.Page.SETTINGS) }.apply {
            add(K.text(s.host.accountEmail.take(1).uppercase().ifEmpty { io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.name.take(1).uppercase() }, 19f, Color.white, 600))
            name = "设置"
        }
        bar.add(avatar).size(dp(50f))
        bar.add().growX()
        val newChat = Tap(K.light, 25f) { s.newChat() }.apply {
            add(K.svg(PI.plus, 20f, K.onLight, 1.8f)).padRight(dp(10f))
            add(K.text("新建对话", 17f, K.onLight, 600))
            margin(0f, dp(18f), 0f, dp(22f))
            name = "新建对话"
        }
        bar.add(newChat).height(dp(50f))
        val footer = Stack()
        footer.add(fade)
        footer.add(Table().bottom().apply { add(bar).growX().pad(0f, dp(18f), dp(20f), dp(18f)) })
        add(Table().bottom().apply { add(footer).growX().height(dp(110f)) })
    }

    override fun act(delta: Float) {
        super.act(delta)
        if (s.page != shownPage) {
            shownPage = s.page
            buildNav()
        }
        val running = s.host.wb.conversations.filter { it.status.active }.joinToString(",") { it.id }
        val key = "${s.host.wb.version}|${s.page}|${s.conversation()?.id}|$running"
        if (key != shownRecent) {
            shownRecent = key
            buildRecent()
        }
    }

    private fun buildNav() {
        nav.clearChildren()
        val items = listOf(
            Triple("对话", PI.chat, listOf(FusedShell.Page.HOME, FusedShell.Page.CHAT)) to { if (s.conversation() != null) s.show(FusedShell.Page.CHAT) else s.show(FusedShell.Page.HOME) },
            Triple("项目", PI.box, listOf(FusedShell.Page.PROJECTS)) to { s.show(FusedShell.Page.PROJECTS) },
            Triple("产物", PI.layers, listOf(FusedShell.Page.LIBRARY)) to { s.show(FusedShell.Page.LIBRARY) },
            Triple("更多", PI.grid, listOf(FusedShell.Page.MORE, FusedShell.Page.TOOL)) to { s.show(FusedShell.Page.MORE) },
        )
        val extra = s.host.drawerItems().map { d -> Triple(d.label, d.icon, emptyList<FusedShell.Page>()) to { s.closeDrawer(); s.host.ui?.let(d.open); Unit } }
        for ((info, go) in items + extra) {
            val (label, icon, pages) = info
            val t = Tap(if (s.page in pages) K.surface else null, 12f) { go() }
            t.name = label
            t.margin(0f, dp(14f), 0f, dp(14f))
            t.add(K.svg(icon, 24f, K.text, 1.5f)).padRight(dp(18f))
            // 中文字形视觉重心偏低，往上提一点和图标对齐
            t.add(K.text(label, 17f, K.text)).growX().left().padBottom(dp(3f))
            nav.add(t).growX().height(dp(50f)).row()
        }
    }

    private fun buildRecent() {
        recent.clearChildren()
        val convs = s.host.wb.conversations.filter { !it.archived && it.turns.isNotEmpty() }.sortedByDescending { it.updatedAt }.take(40)
        if (convs.isEmpty()) {
            recent.add(K.text("还没有对话", 15f, K.subtle)).left().padLeft(dp(14f)).padTop(dp(6f))
            return
        }
        for (c in convs) {
            val active = s.page == FusedShell.Page.CHAT && s.conversation()?.id == c.id
            val t = Tap(if (active) K.surface3 else null, 12f) { s.openChat(c.id) }
            t.margin(0f, dp(14f), 0f, dp(14f))
            t.add(K.svg(PI.chat, 20f, K.muted, 1.6f)).padRight(dp(18f))
            val title = c.title.takeIf { it.isNotBlank() && it != "新会话" } ?: "新对话"
            t.add(K.text(title, 16f, K.text).apply { setEllipsis(true) }).growX().minWidth(0f).left()
            // 助手正在这个对话里干活：最右边一个转圈的小空心圆
            if (c.status.active) t.add(io.github.zenthxsin.rustwright.ui.kit.SpinRing(16f, K.muted)).size(dp(16f)).padLeft(dp(10f))
            recent.add(t).growX().height(dp(46f)).row()
        }
        recent.add().height(dp(110f))
    }
}

internal class Divider(private val color2: Color = K.divider) : Element() {
    init {
        touchable = Touchable.disabled
    }

    override fun draw() {
        Draw.color(color2.r, color2.g, color2.b, color2.a * parentAlpha)
        Fill.crect(x, y, width, height)
        Draw.reset()
    }
}

/** 版本号（由启动器写入）。 */
object Version {
    @Volatile var name: String = "0.4.0"
}

// ---------------- 产物 ----------------

internal class LibraryPage(private val s: FusedShell) : Table() {
    private val list = Table().top()
    private val search = TextField(s.libraryQuery, TextField.TextFieldStyle().apply {
        font = RwTheme.text(16f, 400)
        fontColor = K.text
        messageFont = RwTheme.text(16f, 400)
        messageFontColor = K.subtle
        cursor = RwTheme.solid(K.text).also { it.minWidth = dp(1.5f) }
        selection = RwTheme.solid(K.blue.cpy().a(0.3f))
    }).apply { messageText = "搜索产物"; name = "搜索产物" }

    init {
        top()
        s.libraryView = this
        // 搜索框（参考 Claude App：圆角描边胶囊，左边放大镜）
        val bar = Tap(null, 26f) { arc.Core.scene.setKeyboardFocus(search); TextField.inputBridge?.begin(search) }
        bar.border = K.inputBorder
        bar.pressOverlay = false
        bar.margin(0f, dp(18f), 0f, dp(18f))
        bar.add(K.svg(PI.search, 20f, K.subtle, 1.8f)).padRight(dp(12f))
        bar.add(search).growX().height(dp(48f))
        search.changed { s.libraryQuery = search.text; rebuild() }
        add(bar).growX().height(dp(52f)).pad(dp(4f), dp(16f), dp(14f), dp(16f)).row()
        add(scrollPage(list)).grow()
        rebuild()
    }

    private var libCache = HashMap<String, List<Artifact>>()
    private var libKey = ""

    fun refreshFiles() { libCache.clear(); rebuild() }

    /** 每个项目的产物（按文档版本缓存，换类型 / 搜索不重读）。 */
    private fun lib(p: io.github.zenthxsin.rustwright.core.codex.CodexProject): List<Artifact> {
        val key = "${s.host.wb.version}"
        if (key != libKey) { libKey = key; libCache = HashMap() }
        return libCache.getOrPut(p.id) { s.host.libraryOf(p) }
    }

    fun rebuild() {
        // 范围指向的项目被删了 / 本会话没有产物：回到项目列表
        if (s.libraryScope != LIB_HOME && s.host.wb.projects.none { it.id == s.libraryScope }) s.libraryScope = LIB_HOME
        if (search.text != s.libraryQuery) search.text = s.libraryQuery
        search.messageText = if (s.libraryScope == LIB_HOME) "搜索项目" else "搜索产物"
        list.clearChildren()
        if (s.libraryScope == LIB_HOME) homeList() else artifactList()
    }

    /** 产物首页：一个项目一张卡（缩略图是最新的贴图 / 地图），点进去看这个项目的产物。 */
    private fun homeList() {
        val q = s.libraryQuery.trim()
        val projects = s.host.wb.projects.filter { !it.archived && (q.isEmpty() || it.name.contains(q, true)) }
        list.add(K.text("${projects.size} 个项目", 13f, K.subtle)).left().pad(0f, dp(20f), dp(10f), dp(16f)).row()
        if (projects.isEmpty()) {
            list.add(K.para(if (q.isNotEmpty()) "没有名字里带「$q」的项目。" else "还没有项目。到「项目」里新建一个。", 14f, K.subtle, lh = 22f)).growX().pad(dp(20f))
            return
        }
        val colors = listOf("A8532F", "3E6A8C", "5E7A6A", "6A5A8C", "8C6A3E")
        val all = s.host.wb.projects.filter { !it.archived }
        for (p in projects) {
            val items = lib(p)
            val color = K.c(colors[all.indexOf(p).coerceAtLeast(0) % colors.size])
            // 分类统计（几个单位 / 几张贴图 / 几张地图）已经说明了数量，前面再加「N 项」会把后面挤成省略号
            val sub = s.host.projectStats(p).ifBlank { "${items.size} 项" }
            list.add(folder(p.name, sub, items, color) { s.libraryScope = p.id; s.libraryKind = null; s.libraryQuery = ""; rebuild() }).growX().pad(0f, dp(16f), dp(12f), dp(16f)).row()
        }
    }

    private fun artifactList() {
        val q = s.libraryQuery.trim()
        val src = s.host.wb.projects.firstOrNull { it.id == s.libraryScope }?.let { lib(it) }.orEmpty()
        val items = src.filter { a -> (s.libraryKind == null || a.kind == s.libraryKind) && (q.isEmpty() || a.name.contains(q, true)) }
            // 源列表已按修改时间倒序；稳定排序保留每种类型内的新旧顺序。
            .sortedBy { it.kind.order }
        val head = listOfNotNull(s.libraryKind?.label ?: "所有类型", "${items.size} 项").joinToString(" · ")
        list.add(K.text(head, 13f, K.subtle)).left().pad(0f, dp(20f), dp(10f), dp(16f)).row()
        if (items.isEmpty()) {
            list.add(K.para(if (q.isNotEmpty()) "没有名字里带「$q」的产物。" else "这个项目还没有产物。创建或导入文件后，会显示在这里。", 14f, K.subtle, lh = 22f))
                .growX().pad(dp(20f))
            return
        }
        for (a in items.take(300)) list.add(card(a, null)).growX().pad(0f, dp(16f), dp(12f), dp(16f)).row()
    }

    /** 项目卡：左边缩略图（最新一张有预览的产物；没有就是项目色块 + 首字），右边名字、数量和统计。 */
    private fun folder(name: String, sub: String, items: List<Artifact>, color: Color?, open: () -> Unit): Element {
        val card = Tap(K.surface, 16f) { open() }
        card.name = "项目产物:$name"
        card.border = K.cardBorder
        card.margin(dp(12f))
        val thumb = Stack()
        val pv = items.firstOrNull { it.preview != null }
        val box = Table(K.fill(if (pv == null && color != null) color else K.input, 12f))
        box.clipRadius = dp(12f)
        val el = pv?.preview?.invoke()
        when {
            el != null -> box.add(el).grow()
            color != null -> box.add(K.text(name.take(1), 26f, Color.white, 600))
            else -> box.add(K.svg(PI.chat, 28f, K.subtle, 1.5f))
        }
        thumb.add(box)
        val badge = Table().bottom().left()
        badge.add(Table(K.fill(K.dl("2E2A5C", "E4E0FF"), 9f)).apply { add(K.svg(if (color == null) PI.chat else PI.box, 16f, K.dl("B7AEFF", "5B4BD6"), 1.8f)) })
            .size(dp(30f)).pad(0f, dp(-4f), dp(-4f), 0f)
        thumb.add(badge)
        card.add(thumb).size(dp(112f), dp(80f)).padRight(dp(14f))
        val info = Table().left()
        info.add(K.text(name, 16f, K.text, 500, 19f).apply { setEllipsis(true) }).growX().minWidth(0f).left().row()
        info.add(K.text(sub, 12f, K.subtle, lh = 15f).apply { setEllipsis(true) }).growX().minWidth(0f).left().padTop(dp(6f))
        card.add(info).growX().minWidth(0f)
        card.add(K.svg(PI.chevronRight, 16f, K.subtle, 2f)).padLeft(dp(8f))
        return card
    }

    /** 一张产物卡：左边缩略图（没有预览的显示类型图标），左下角类型角标；右边名字、项目 · 说明。 */
    private fun card(a: Artifact, project: String?): Element {
        val card = Tap(K.surface, 16f) { s.openArtifact(a) }
        card.name = "产物:${a.name}"
        card.onLongPress = { s.artifactMenu(a, card) }
        card.border = K.cardBorder
        card.margin(dp(12f))
        val thumb = Stack()
        val box = Table(K.fill(if (a.kind == Artifact.Kind.IMAGE) K.dl("2B2F27", "E6E8E0") else K.input, 12f))
        box.clipRadius = dp(12f)
        val pv = a.preview?.invoke()
        if (pv != null) box.add(pv).grow() else box.add(K.svg(kindIcon(a.kind), 28f, K.subtle, 1.5f))
        thumb.add(box)
        val badge = Table().bottom().left()
        badge.add(Table(K.fill(K.dl("2E2A5C", "E4E0FF"), 9f)).apply { add(K.svg(kindIcon(a.kind), 16f, K.dl("B7AEFF", "5B4BD6"), 1.8f)) })
            .size(dp(30f)).pad(0f, dp(-4f), dp(-4f), 0f)
        thumb.add(badge)
        card.add(thumb).size(dp(112f), dp(80f)).padRight(dp(14f))
        val info = Table().left()
        info.add(K.text(a.name, 16f, K.text, 500, 19f).apply { setEllipsis(true) }).growX().minWidth(0f).left().row()
        val sub = listOfNotNull(project, a.meta).joinToString(" · ")
        info.add(K.text(sub, 12f, K.subtle, lh = 15f).apply { setEllipsis(true) }).growX().minWidth(0f).left().padTop(dp(6f))
        card.add(info).growX().minWidth(0f)
        return card
    }

    companion object {
        fun kindIcon(k: Artifact.Kind?) = k?.icon ?: PI.layers
    }
}

// ---------------- 项目 ----------------

internal class ProjectsPage(private val s: FusedShell) : Stack() {
    init {
        // 原型：padding 8 20 110；组标题 15/500 灰，margin 8 6 12；卡片深底 0E0E0D 圆角 14，内边距 14 16，间距 14
        val col = Table().top()
        col.margin(dp(8f), dp(20f), dp(110f), dp(20f))
        col.add(K.text("模组项目", 15f, K.subtle, 500)).left().pad(dp(8f), dp(6f), dp(12f), dp(6f)).row()
        val current = s.currentProject()?.id
        // 按创建顺序，颜色跟着顺序固定（原型 PROJECTS）
        val projects = s.host.wb.projects.filter { !it.archived }
        if (projects.isEmpty()) col.add(K.para("还没有项目。点右下角「新建项目」，起个名字就能开始做模组。", 14f, K.subtle, lh = 22f)).growX().pad(dp(4f), dp(6f), dp(6f), dp(6f)).row()
        val colors = listOf("A8532F", "3E6A8C", "5E7A6A", "6A5A8C", "8C6A3E")
        projects.forEachIndexed { i, p ->
            val t = Tap(K.bubble, 14f) { s.host.setCurrentProject(p.id); s.show(FusedShell.Page.HOME) }
            t.border = if (p.id == current) K.accent else K.bubble
            t.name = p.name
            t.onLongPress = { projectMenu(p, t) }
            t.margin(dp(14f), dp(16f), dp(14f), dp(16f))
            t.add(Table(K.fill(K.c(colors[i % colors.size]), 10f)).apply { add(K.text(p.name.take(1), 18f, Color.white, 600)) }).size(dp(46f)).padRight(dp(14f))
            val c = Table().left()
            val head = Table()
            head.add(K.text(p.name, 16f, K.text, 500, 19.2f).apply { setEllipsis(true) }).growX().minWidth(0f).left()
            // 橙色描边之外再写明「当前」：第一次用的人不知道描边代表什么
            if (p.id == current) head.add(K.text("当前", 12f, K.accent, 500)).padLeft(dp(8f))
            // 这个项目里有对话正在执行：右边转圈的小空心圆（页面不重建，按帧检查）
            val spin = io.github.zenthxsin.rustwright.ui.kit.SpinRing(16f, K.muted)
            spin.update { spin.visible = s.host.wb.conversationsOf(p.id).any { it.status.active } }
            head.add(spin).size(dp(16f)).padLeft(dp(10f))
            c.add(head).growX().row()
            c.add(K.text(s.host.projectStats(p).ifBlank { "${s.host.wb.conversationsOf(p.id).size} 个对话" }, 13f, K.subtle, lh = 15.6f).apply { setEllipsis(true) }).growX().minWidth(0f).left().padTop(dp(5f))
            t.add(c).growX().minWidth(0f)
            // 看得见的菜单入口（长按同样能打开）：文件、产物、导出、重命名、删除
            val more = Tap(null, 10f) { projectMenu(p, t) }
            more.name = "项目菜单:${p.name}"
            more.add(K.svg(PI.dots, 20f, K.muted, 2f))
            // 点 ⋮ 不算点卡片（否则会同时切到这个项目的首页）
            more.addListener(object : arc.scene.event.InputListener() {
                override fun touchDown(event: arc.scene.event.InputEvent, x: Float, y: Float, pointer: Int, button: arc.input.KeyCode): Boolean { event.stop(); return false }
            })
            t.add(more).size(dp(40f)).padLeft(dp(4f)).padRight(dp(-8f))
            col.add(t).growX().padBottom(dp(10f)).row()
        }
        // 运行环境放在项目后面：做模组的人先看到的是自己的项目
        col.add(K.text("运行环境", 15f, K.subtle, 500)).left().pad(dp(16f), dp(6f), dp(12f), dp(6f)).row()
        val env = s.host.env()
        val envCard = Table(K.fill(K.bubble, 14f))
        envCard.margin(dp(14f), dp(16f), dp(14f), dp(16f))
        envCard.add(Table(K.fill(K.surface, 10f)).apply { add(K.svg(PI.term, 22f, K.muted, 1.6f)) }).size(dp(44f)).padRight(dp(14f))
        val info = Table().left()
        info.add(K.text("Alpine Linux", 16f, K.text, 500, 19.2f).apply { setEllipsis(true) }).growX().minWidth(0f).left().row()
        val status = Table()
        status.add(StatusDot(if (env.ready) K.green else K.amber)).size(dp(7f)).padRight(dp(6f))
        status.add(K.text(env.detail, 13f, K.subtle, lh = 15.6f).apply { setEllipsis(true) }).growX().minWidth(0f)
        info.add(status).growX().left().minWidth(0f).padTop(dp(5f))
        envCard.add(info).growX().minWidth(0f).left().row()
        val term = Tap(K.surface2, 10f) { openTerminal() }
        term.add(K.text("打开终端", 14f, K.text))
        term.margin(0f, dp(14f), 0f, dp(14f))
        term.name = "打开终端"
        envCard.add(term).colspan(2).growX().height(dp(38f)).padTop(dp(12f))
        col.add(envCard).growX().row()
        add(scrollPage(col))
        val fab = Tap(K.light, 26f) {
            s.launch { s.host.createProject()?.let { s.host.setCurrentProject(it.id); s.show(FusedShell.Page.PROJECTS, arc.scene.ui.layout.Switcher.NONE) } }
        }
        fab.name = "新建项目"
        fab.add(K.svg(PI.plus, 20f, K.onLight, 1.8f)).padRight(dp(10f))
        fab.add(K.text("新建项目", 17f, K.onLight, 600, 17f)).padBottom(dp(2f))
        fab.margin(0f, dp(18f), 0f, dp(22f))
        add(Table().bottom().right().apply { add(fab).height(dp(52f)).pad(0f, 0f, dp(24f), dp(20f)) })
    }

    private fun openTerminal() {
        s.openToolId("terminal", FusedShell.Page.PROJECTS)
    }

    /** 项目菜单（卡片右边的 ⋮ 或长按）：管理文件 / 查看产物 / 导出模组 / 导出工程 / 重命名 / 删除。 */
    private fun projectMenu(p: io.github.zenthxsin.rustwright.core.codex.CodexProject, anchor: arc.scene.Element) {
        s.popup(s.host.projectActions(p) + listOf(
            MenuHost.Item("管理文件", PI.folder) { s.host.setCurrentProject(p.id); s.openToolId("files", FusedShell.Page.PROJECTS) },
            MenuHost.Item("查看产物", PI.layers) { s.libraryScope = p.id; s.libraryKind = null; s.libraryQuery = ""; s.show(FusedShell.Page.LIBRARY) },
            MenuHost.Item("导出源文件", PI.archive) { s.launch { s.host.exportProjectSource(p) } },
            MenuHost.Item("重命名", PI.edit) { s.launch { s.host.renameProject(p); s.show(FusedShell.Page.PROJECTS, arc.scene.ui.layout.Switcher.NONE) } },
            MenuHost.Item("删除", PI.trash, K.danger) { s.launch { s.host.deleteProject(p); s.show(FusedShell.Page.PROJECTS, arc.scene.ui.layout.Switcher.NONE) } },
        ), anchor)
    }
}

private class StatusDot(private val c: Color) : Element() {
    override fun draw() = arc.graphics.g2d.Shapes.circle(x + width / 2f, y + height / 2f, width / 2f, Color(c).a(parentAlpha))
}

// ---------------- 设置 ----------------

internal class SettingsPage(private val s: FusedShell) : Table() {
    init {
        // 原型：padding 6 14 32，各块间距 14
        val col = Table().top()
        // 账号卡：头像 · 名字（未登录时是「登录 NewAPI」）· 余额或一句好处，点开个人资料
        val signedIn = s.host.accountEmail.isNotBlank()
        val account = Tap(K.surface, 14f) { s.host.openProfile() }
        account.name = "个人资料"
        account.margin(0f, dp(16f), 0f, dp(14f))
        val avatar = Table(K.fill(if (signedIn) K.accent else K.surface3, 22f))
        if (signedIn) avatar.add(K.text(s.host.accountEmail.take(1).uppercase(), 19f, Color.white, 600))
        else avatar.add(K.svg(PI.user, 22f, K.muted, 1.7f))
        account.add(avatar).size(dp(44f)).padRight(dp(14f))
        val who = Table().left()
        who.add(K.text(if (signedIn) s.host.accountEmail else "登录 NewAPI", 17f, K.text, 500, 17f).apply { setEllipsis(true) }).growX().minWidth(0f).left().row()
        val b = s.host.balance()
        val sub = when {
            // 余额在下面「充值与账单」一行，这里不重复
            signedIn -> "NewAPI 账号"
            else -> "领免费额度，自动获取专用令牌"
        }
        who.add(K.text(sub, 13f, K.subtle, lh = 13f).apply { setEllipsis(true) }).growX().minWidth(0f).left().padTop(dp(6f))
        account.add(who).growX().minWidth(0f)
        account.add(K.svg(PI.chevronRight, 18f, K.subtle, 2f)).padLeft(dp(8f))
        col.add(account).growX().height(dp(76f)).pad(dp(6f), dp(14f), 0f, dp(14f)).row()
        // 分组小标题 + 列表（参考 Claude App 设置：一行一个入口，短值放右侧，长说明放第二行）
        val titles = s.host.settingTitles()
        s.host.settings().forEachIndexed { i, group ->
            titles.getOrNull(i)?.let { col.add(K.text(it, 13f, K.subtle, 500, 13f)).left().pad(dp(22f), dp(20f), dp(8f), dp(20f)).row() }
            val g = ListGroup()
            for (r in group) {
                // 当前值一律放右侧（过长省略），每行等高
                g.row(listRow(r.title, null, r.icon, trailing = r.sub(), chevron = true, minHeight = 56f, liveTrailing = r.sub) { r.open() })
            }
            col.add(g).growX().pad(if (titles.getOrNull(i) != null) 0f else dp(14f), dp(14f), 0f, dp(14f)).row()
        }
        col.add(K.text(io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.name + " " + Version.name, 13f, K.subtle)).height(dp(44f)).padTop(dp(14f)).padBottom(dp(32f))
        add(scrollPage(col)).grow()
    }
}

// ---------------- 更多 ----------------

internal class MorePage(private val s: FusedShell) : Table() {
    init {
        // 原型：padding 4 16 28；说明 13/20；组标题 margin 20 6 10；两列网格 gap 10
        val col = Table().top().left()
        col.add(K.para("想自己动手时用。做出来的东西和${AppBrand.identity.assistant}的产物在同一个项目里，随时可以交给${AppBrand.identity.assistant}接着改。", 13f, K.subtle, lh = 20f))
            .growX().pad(dp(10f), dp(22f), dp(4f), dp(22f)).row()
        for (g in s.host.tools()) {
            col.add(K.text(g.title, 15f, K.subtle, 500, 15f)).left().pad(dp(20f), dp(22f), dp(10f), dp(22f)).row()
            // 自动分列（ui.kit.reactive.AutoGrid）：按最小格宽 150dp 算列数，手机两列，平板 / 横屏自动变多列
            val grid = io.github.zenthxsin.rustwright.ui.kit.reactive.AutoGrid(minCell = dp(150f), gap = dp(10f), maxColumns = 5)
            for (t in g.tools) {
                val card = Tap(K.surface, 14f) { s.openToolEntry(t, g) }
                card.name = t.name
                card.margin(dp(14f))
                card.minH = dp(104f)
                card.top().left()
                card.add(Table(K.fill(g.tint, 10f)).apply { add(K.svg(t.icon, 21f, g.ink, 1.6f)) }).size(dp(38f)).left().row()
                card.add(K.text(t.name, 15f, K.text, 500, 18f)).left().padTop(dp(12f)).row()
                card.add(K.para(t.desc, 12f, K.subtle, lh = 16.2f)).growX().left().padTop(dp(4f))
                grid.add(card)
            }
            col.add(grid).growX().pad(0f, dp(16f), 0f, dp(16f)).row()
        }
        col.add().height(dp(28f))
        add(scrollPage(col)).grow()
    }
}

/** 原型「工具：其它（占位）」：图标、名称、说明和「让助手代劳」。 */
internal class GenericToolPage(s: FusedShell, t: ToolEntry, g: ToolGroup) : Table() {
    init {
        val col = Table()
        col.add(Table(K.fill(g.tint, 16f)).apply { add(K.svg(t.icon, 32f, g.ink, 1.5f)) }).size(dp(64f)).row()
        col.add(K.text(t.name, 20f, K.text, 600)).padTop(dp(16f)).row()
        col.add(K.para(t.desc + "。这个工具的独立界面还在做，现在可以先交给${AppBrand.identity.assistant}处理。", 14f, K.muted, lh = 22f).apply { setAlignment(arc.util.Align.center) })
            .width(dp(318f)).padTop(dp(16f)).row()
        val go = Tap(K.light, 12f) { s.askRust(t.ask.ifBlank { "帮我用「${t.name}」处理一下" }) }
        go.add(K.text("让${AppBrand.identity.assistant}代劳", 15f, K.onLight, 600))
        go.margin(0f, dp(22f), 0f, dp(22f))
        go.name = "让${AppBrand.identity.assistant}代劳"
        col.add(go).height(dp(46f)).padTop(dp(22f))
        add(col).expand().center().padBottom(dp(80f))
    }
}
