package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Shapes
import arc.scene.Element
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.codex.CodexMcpServer
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.SheetPage
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.chip
import io.github.zenthxsin.rustwright.ui.kit.listRow
import io.github.zenthxsin.rustwright.ui.kit.pageBar
import io.github.zenthxsin.rustwright.ui.kit.sectionTitle
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import kotlinx.coroutines.launch

/**
 * 设置 › 连接器：助手实际在用的 MCP 服务器（Codex config.toml 里的 [mcp_servers.*]），
 * 显示真实连接状态和工具数；可以添加（地址或命令）、删除、重新连接。
 */
internal class ConnectorsPage(private val s: FusedShell) : Table(), io.github.zenthxsin.rustwright.ui.kit.OwnHeader {
    private val list = Table().top()
    private val builtin = Table().top()
    private var servers: List<CodexMcpServer>? = null
    private var error: String? = null
    private var loading = false
    private var seenVersion = -1
    private var lastRefresh = 0L

    /** 页面开着时，Codex 推来新的 MCP 连接状态就自动刷新，最多 3 秒一次。 */
    override fun act(delta: Float) {
        super.act(delta)
        val v = s.host.wb.mcpStatusVersion
        if (v != seenVersion && !loading && System.currentTimeMillis() - lastRefresh > 3000) {
            seenVersion = v
            refresh()
        }
    }

    /** 顶栏：返回 · 连接器 · 重新连接。添加服务器放在列表最后一行。 */
    private val bar = io.github.zenthxsin.rustwright.ui.kit.PageBar("连接器")

    override fun bindBack(back: () -> Unit) = bar.bindBack(back)

    init {
        top()
        pageBar(bar)
        bar.action(PI.refresh, "重新连接") { reload() }
        val col = Table().top()
        col.margin(dp(4f), dp(16f), dp(24f), dp(16f))
        col.add(K.para("${AppBrand.identity.assistant}通过 MCP 服务器用工具。内置的几组由 Workbench 提供，也可以添加外部的：http(s) 地址，或在虚拟环境里运行的命令。", 14f, K.subtle, lh = 21f))
            .growX().pad(0f, dp(4f), dp(14f), dp(4f)).row()
        col.add(sectionTitle("内置")).left().pad(0f, dp(4f), dp(8f), dp(4f)).row()
        col.add(builtin).growX().row()
        col.add(sectionTitle("MCP 服务器")).left().pad(dp(22f), dp(4f), dp(8f), dp(4f)).row()
        col.add(list).growX().row()
        add(ScrollPane(col).apply { setScrollingDisabledX(true); setOverscroll(false, true) }).grow()
        refresh()
    }

    private fun refresh() {
        if (loading) return
        loading = true
        lastRefresh = System.currentTimeMillis()
        seenVersion = s.host.wb.mcpStatusVersion
        build()
        s.host.scope.launch {
            val r = runCatching { s.host.wb.mcpServers() }
            Core.app.post {
                loading = false
                r.onSuccess { servers = it; error = null }.onFailure { error = it.message ?: it.javaClass.simpleName }
                build()
            }
        }
    }

    private fun build() {
        builtin.clearChildren()
        val sets = s.host.toolsets()
        val ids = sets.map { it.id }.toSet()
        val bg = ListGroup()
        sets.forEach { bg.row(builtinRow(it)) }
        if (sets.isNotEmpty()) builtin.add(bg).growX()
        list.clearChildren()
        val ss = servers?.filter { it.name !in ids }
        when {
            // 没登录不算出错：用普通说明色，只有真的读失败才标红
            ss == null && error != null -> list.add(K.para(if ("登录" in error.orEmpty()) "登录后显示你添加的 MCP 服务器" else "读不到 MCP 状态：$error", 14f, if ("登录" in error.orEmpty()) K.subtle else K.danger, lh = 22f)).growX().left().padBottom(dp(12f)).row()
            ss == null -> list.add(K.text("正在读取…", 15f, K.subtle)).left().pad(0f, dp(4f), dp(12f), dp(4f)).row()
            ss.isEmpty() -> list.add(K.text("还没有 MCP 服务器", 15f, K.subtle)).left().pad(0f, dp(4f), dp(12f), dp(4f)).row()
        }
        // 服务器列表，最后一行永远是「添加服务器」（读不到状态时也能加）
        val g = ListGroup()
        ss?.forEach { g.row(row(it)) }
        g.row(addRow())
        list.add(g).growX()
    }

    private fun row(m: CodexMcpServer): Element {
        val err = m.error?.takeIf { it.isNotBlank() }
        val (label, color) = when {
            m.status == "disabled" -> "已停用" to K.subtle
            m.status == "starting" -> "正在连接…" to K.c("E9A23B")
            m.status == "authenticationRequired" -> "需要登录" to K.c("E9A23B")
            m.status == "failed" || err != null -> "连接失败，点开看原因" to K.danger
            m.status == "connected" || m.tools > 0 -> "已连接 · ${m.tools} 个工具" to K.green
            else -> "未启动（发消息时自动连接）" to K.subtle
        }
        // 左边圆角图标块，右边名称 + 「● 状态」，最右箭头
        val r = Tap(K.surface, 0f) { actions(m) }
        r.name = m.name
        r.margin(dp(12f), dp(16f), dp(12f), dp(14f))
        r.add(iconBox(PI.plug)).size(dp(40f)).padRight(dp(14f))
        val col = Table().left()
        col.add(K.text(m.name, 16f, K.text, 500, 16f).apply { setEllipsis(true) }).growX().minWidth(0f).left().row()
        val st = Table().left()
        st.add(StatusDotSmall(color)).size(dp(7f)).padRight(dp(6f))
        st.add(K.text(label, 13f, K.subtle, lh = 13f).apply { setEllipsis(true) }).growX().minWidth(0f).left()
        col.add(st).growX().minWidth(0f).left().padTop(dp(6f))
        r.add(col).growX().minWidth(0f)
        r.add(K.svg(PI.chevronRight, 16f, K.subtle, 2f)).padLeft(dp(8f))
        return r
    }

    private fun builtinRow(t: BuiltinToolset): Element {
        val m = servers?.firstOrNull { it.name == t.id }
        val err = m?.error?.takeIf { it.isNotBlank() }
        val (label, color) = when {
            !t.enabled -> "已停用" to K.subtle
            m?.status == "failed" || err != null -> "连接失败，点开看原因" to K.danger
            m?.status == "starting" -> "正在连接…" to K.c("E9A23B")
            m != null && (m.status == "connected" || m.tools > 0) -> "已连接 · ${m.tools} 个工具" to K.green
            else -> "${t.tools} 个工具 · 发消息时自动连接" to K.subtle
        }
        val r = Tap(K.surface, 0f) { builtinActions(t, err) }
        r.name = t.title
        r.margin(dp(12f), dp(16f), dp(12f), dp(14f))
        r.add(iconBox(PI.box)).size(dp(40f)).padRight(dp(14f))
        val col = Table().left()
        col.add(K.text(t.title, 16f, K.text, 500, 16f).apply { setEllipsis(true) }).growX().minWidth(0f).left().row()
        val st = Table().left()
        st.add(StatusDotSmall(color)).size(dp(7f)).padRight(dp(6f))
        st.add(K.text(label, 13f, K.subtle, lh = 13f).apply { setEllipsis(true) }).growX().minWidth(0f).left()
        col.add(st).growX().minWidth(0f).left().padTop(dp(6f))
        r.add(col).growX().minWidth(0f)
        r.add(K.svg(PI.chevronRight, 16f, K.subtle, 2f)).padLeft(dp(8f))
        return r
    }

    private fun builtinActions(t: BuiltinToolset, err: String?) {
        val g = ListGroup()
        g.row(listRow(t.description, "${t.tools} 个工具 · MCP 服务器 ${t.id}", PI.box, minHeight = 64f))
        err?.let { e ->
            g.row(Table(K.fill(K.surface, 0f)).apply {
                margin(dp(12f), dp(18f), dp(12f), dp(18f))
                add(K.text("连接失败的原因", 14f, K.subtle)).left().row()
                add(K.para(e.take(400), 14f, K.danger, lh = 21f)).growX().left().padTop(dp(6f))
            })
        }
        val label = if (t.enabled) "停用" else "启用"
        g.row(listRow(label, null, if (t.enabled) PI.archive else PI.plus, if (t.enabled) K.danger else K.text,
            titleColor = if (t.enabled) K.danger else K.text, minHeight = 56f) {
            s.sheets.close()
            s.host.scope.launch {
                val note = if (t.enabled) "${AppBrand.identity.assistant}之后就用不了这组工具。" else "${AppBrand.identity.assistant}又能用这组工具了。"
                if (!Dialogs.confirm("$label${t.title}", note + "会重启${AppBrand.identity.assistant}，正在进行的任务会停止。", ok = label)) return@launch
                Core.app.post { s.host.setToolsetEnabled(t.id, !t.enabled); servers = null; refresh() }
            }
        })
        s.sheets.open(SheetPage(t.title, Table().top().apply { add(g).growX().pad(0f, dp(10f), dp(20f), dp(10f)) }, height = 360f))
    }

    private fun addRow(): Tap {
        val r = Tap(K.surface, 0f) { addServer() }
        r.name = "添加服务器"
        r.margin(dp(12f), dp(16f), dp(12f), dp(14f))
        r.add(iconBox(PI.plus)).size(dp(40f)).padRight(dp(14f))
        r.add(K.text("添加服务器", 16f, K.text, 500, 16f)).growX().left()
        return r
    }

    private fun iconBox(path: String) = Table(K.fill(K.surface3, 10f)).apply { add(K.svg(path, 20f, K.muted, 1.8f)) }

    private fun actions(m: CodexMcpServer) {
        val g = ListGroup()
        if (m.target.isNotBlank()) g.row(listRow("命令 / 地址", m.target, PI.code, minHeight = 60f))
        m.error?.takeIf { it.isNotBlank() }?.let { e ->
            val box = Table(K.fill(K.surface, 0f)).apply {
                margin(dp(12f), dp(18f), dp(12f), dp(18f))
                add(K.text("连接失败的原因", 14f, K.subtle)).left().row()
                add(K.para(e.take(400), 14f, K.danger, lh = 21f)).growX().left().padTop(dp(6f))
            }
            g.row(box)
        }
        g.row(listRow("删除", null, PI.archive, K.danger, titleColor = K.danger, minHeight = 56f) {
            s.sheets.close()
            s.host.scope.launch {
                if (!Dialogs.confirm("删除 ${m.name}", "${AppBrand.identity.assistant}之后就用不了它的工具了", ok = "删除")) return@launch
                val r = runCatching { s.host.wb.removeMcpServer(m.name) }
                Core.app.post { r.onFailure { s.notify("删除失败：${it.message}") }; refresh() }
            }
        })
        s.sheets.open(SheetPage(m.name, Table().top().apply { add(g).growX().pad(0f, dp(10f), dp(20f), dp(10f)) }, height = 420f))
    }

    private fun addServer() {
        s.host.scope.launch {
            val name = Dialogs.input("服务器名称", hint = "如 github")?.trim()?.takeIf { it.isNotEmpty() } ?: return@launch
            if (!name.matches(Regex("[A-Za-z0-9_-]+"))) return@launch Core.app.post { s.notify("名称只能用字母、数字、- 和 _") }
            val target = Dialogs.input("地址或命令", hint = "https://…/mcp 或 npx -y @modelcontextprotocol/server-xxx")?.trim()?.takeIf { it.isNotEmpty() } ?: return@launch
            val r = runCatching { s.host.wb.putMcpServer(name, target) }
            Core.app.post { r.fold({ s.notify("已添加 $name") }, { s.notify("添加失败：${it.message}") }); refresh() }
        }
    }

    private fun reload() {
        s.host.scope.launch {
            runCatching { s.host.wb.reloadMcp() }
            Core.app.post { s.notify("正在重新连接"); refresh() }
        }
    }

    private class StatusDotSmall(private val c: Color) : Element() {
        override fun draw() {
            Shapes.circle(x + width / 2f, y + height / 2f, width / 2f, Color(c).a(parentAlpha * color.a))
        }
    }
}
