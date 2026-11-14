package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.Core
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.account.CustomRelayRoute
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.OwnHeader
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.PageBar
import io.github.zenthxsin.rustwright.ui.kit.listRow
import io.github.zenthxsin.rustwright.ui.kit.pageBar
import io.github.zenthxsin.rustwright.ui.kit.sectionTitle
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import io.github.zenthxsin.rustwright.ui.widgets.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 开发者工具：只有登录的 NewAPI 账号是管理员时才出现在设置里。
 * 这里只放本机的诊断和调试开关；真正的管理操作仍在 NewAPI 后台，由服务端校验权限。
 */
internal class DevToolsPage(
    private val s: FusedShell,
    private val scope: CoroutineScope,
    /** 诊断信息：标题 → 当前值（每秒刷新）。 */
    private val diagnostics: () -> List<Pair<String, String>>,
    private val copyText: (String) -> Unit,
    private val openAdmin: () -> Unit,
    private val restartCodex: () -> Unit,
    private val openTerminal: () -> Unit,
    /** 管理员自定义线路（付费模型改走自填的地址和密钥）。 */
    private val route: CustomRelayRoute,
    /** 线路变了：重启 Codex、重新拉模型列表。 */
    private val routeChanged: () -> Unit,
    /** 用当前填的地址和密钥请求一次模型列表，返回模型数。 */
    private val testRoute: suspend (String, String) -> Int,
) : Table(), OwnHeader {
    private val bar = PageBar("开发者工具")

    override fun bindBack(back: () -> Unit) = bar.bindBack(back)

    init {
        top()
        pageBar(bar)
        val col = Table().top()
        col.margin(dp(8f), dp(16f), dp(28f), dp(16f))

        col.add(sectionTitle("诊断")).left().pad(0f, dp(4f), dp(8f), dp(4f)).row()
        val info = ListGroup()
        for ((title, _) in diagnostics()) {
            info.row(listRow(title, null, null, trailing = value(title), minHeight = 52f, liveTrailing = { value(title) }))
        }
        col.add(info).growX().row()

        col.add(sectionTitle("操作")).left().pad(dp(22f), dp(4f), dp(8f), dp(4f)).row()
        val ops = ListGroup()
        ops.row(listRow("复制诊断信息", "版本、线路、环境状态，发给别人排查问题", PI.copy, minHeight = 64f) {
            copyText(diagnostics().joinToString("\n") { (k, v) -> "$k：$v" })
            Toasts.show("已复制")
        })
        ops.row(listRow("NewAPI 管理后台", "在浏览器里打开渠道、用户、日志管理", PI.server, chevron = true, minHeight = 64f) { openAdmin() })
        ops.row(listRow("重启 Codex 内核", "重新读取线路和配置；运行中的任务会中断", PI.refresh, minHeight = 64f) {
            scope.launch {
                if (Dialogs.choose("重启 Codex 内核？", "正在运行的任务会被中断。", listOf("取消", "重启"), highlight = 1) == 1)
                    Core.app.post { restartCodex(); Toasts.show("已重启") }
            }
        })
        ops.row(listRow("终端", "打开虚拟环境的命令行", PI.term, chevron = true, minHeight = 64f) { openTerminal() })
        col.add(ops).growX().row()

        col.add(sectionTitle("自定义线路")).left().pad(dp(22f), dp(4f), dp(8f), dp(4f)).row()
        val custom = ListGroup()
        custom.row(listRow("使用自定义线路", "付费模型改走下面的地址；换号自动关闭", PI.plug,
            trailing = routeOn(), minHeight = 64f, liveTrailing = { routeOn() }) { toggleRoute() })
        custom.row(listRow("地址", null, PI.server, trailing = baseText(), chevron = true, minHeight = 56f, liveTrailing = { baseText() }) { editBase() })
        custom.row(listRow("密钥", null, PI.shield, trailing = keyText(), chevron = true, minHeight = 56f, liveTrailing = { keyText() }) { editKey() })
        custom.row(listRow("测试连接", "请求一次模型列表，确认地址和密钥能用", PI.retry, minHeight = 64f) { test() })
        custom.row(listRow("清除", "删掉填过的地址和密钥并关闭", PI.trash, minHeight = 64f) { clearRoute() })
        col.add(custom).growX().row()

        col.add(sectionTitle("预览")).left().pad(dp(22f), dp(4f), dp(8f), dp(4f)).row()
        val preview = ListGroup()
        preview.row(listRow("工具版首页", "首页换成项目和工具，${AppBrand.identity.assistant}收成底部一条", PI.grid,
            trailing = onOff(), minHeight = 64f, liveTrailing = { onOff() }) {
            s.toolFirst = !s.toolFirst
            Toasts.show(if (s.toolFirst) "已切到工具版首页" else "已切回默认首页")
        })
        col.add(preview).growX().row()

        add(ScrollPane(col).apply { setScrollingDisabledX(true); setOverscroll(false, true) }).grow()
    }

    private fun routeOn() = if (route.active != null) "开" else "关"
    private fun baseText() = route.baseUrl?.removePrefix("https://") ?: "未填写"
    private fun keyText() = route.apiKey?.let(CustomRelayRoute::mask) ?: "未填写"

    private fun toggleRoute() {
        val on = route.active == null
        val r = runCatching { route.setEnabled(on) }
        r.exceptionOrNull()?.let { Toasts.show(it.message ?: "开启失败"); return }
        routeChanged()
        Toasts.show(if (on) "已切到自定义线路，Codex 已重启" else "已切回 NewAPI 线路")
    }

    private fun editBase() = scope.launch {
        val raw = Dialogs.input("自定义地址", "https://example.com/v1", route.baseUrl.orEmpty()) ?: return@launch
        Core.app.post {
            val r = runCatching { route.setBaseUrl(raw) }
            r.exceptionOrNull()?.let { Toasts.show(it.message ?: "地址不对"); return@post }
            if (route.active != null) routeChanged()
            Toasts.show(if (CustomRelayRoute.isPlainHttp(r.getOrThrow())) "已保存。http 不加密，密钥可能被截获，尽量用 https" else "已保存")
        }
    }

    private fun editKey() = scope.launch {
        // 不回显旧密钥；留空等于不改
        val raw = Dialogs.input("密钥", "sk-...（留空不改）", "", password = true)?.takeIf { it.isNotBlank() } ?: return@launch
        Core.app.post {
            runCatching { route.setApiKey(raw) }.exceptionOrNull()?.let { Toasts.show(it.message ?: "密钥不对"); return@post }
            if (route.active != null) routeChanged()
            Toasts.show("已保存")
        }
    }

    private fun test() {
        val base = route.baseUrl; val key = route.apiKey
        if (base == null || key.isNullOrBlank()) { Toasts.show("先填好地址和密钥"); return }
        Toasts.show("正在测试…")
        scope.launch {
            val r = runCatching { testRoute(base, key) }
            Core.app.post { Toasts.show(r.fold({ "连接正常：$it 个模型" }, { "连接失败：${it.message ?: it.javaClass.simpleName}" })) }
        }
    }

    private fun clearRoute() = scope.launch {
        if (!Dialogs.confirm("清除自定义线路？", "地址和密钥会被删掉，付费模型回到 NewAPI 线路。", ok = "清除")) return@launch
        Core.app.post {
            val wasOn = route.active != null
            route.clear()
            if (wasOn) routeChanged()
            Toasts.show("已清除")
        }
    }

    private fun value(title: String) = diagnostics().firstOrNull { it.first == title }?.second ?: "—"
    private fun onOff() = if (s.toolFirst) "开" else "关"
}
