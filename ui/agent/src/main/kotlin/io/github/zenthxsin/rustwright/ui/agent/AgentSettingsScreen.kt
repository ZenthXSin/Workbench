package io.github.zenthxsin.rustwright.ui.agent

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.scene.ui.Label
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.account.NewApiAccount
import io.github.zenthxsin.rustwright.core.agent.AgentService
import io.github.zenthxsin.rustwright.core.ai.model.ModelCatalog
import io.github.zenthxsin.rustwright.core.ai.model.ModelCatalogClient
import io.github.zenthxsin.rustwright.core.agent.ExecutionMode
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import io.github.zenthxsin.rustwright.ui.designsystem.BackHandler
import io.github.zenthxsin.rustwright.ui.designsystem.Icons
import io.github.zenthxsin.rustwright.ui.designsystem.Rw
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.SetThemeMode
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import io.github.zenthxsin.rustwright.ui.widgets.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 助手设置：账号登录（自动获取专用令牌）或手填令牌、服务地址、拉取模型并选择对话 / 生图模型、步数上限。 */
class AgentSettingsScreen(
    private val service: AgentService,
    private val account: NewApiAccount,
    private val catalogClient: ModelCatalogClient,
    private val scope: CoroutineScope,
    /** 发主题切换事件（为空时不显示外观设置）。 */
    private val postTheme: ((SetThemeMode) -> Unit)? = null,
    /** 融合页已有外层标题和返回按钮。 */
    private val showHeader: Boolean = true,
    private val onBack: () -> Unit,
) : Table(), BackHandler {

    private var catalog: ModelCatalog? = null

    override fun onBackRequested(leave: () -> Unit): Boolean {
        onBack()
        if (!showHeader) leave()
        return true
    }

    init {
        top()
        val body = Table().top().left().apply { margin(dp(12f)) }
        if (showHeader) {
            add(Rw.topBar("设置", subtitle = "${AppBrand.identity.assistant}", onBack = onBack).apply { background(RwTheme.solid(RwTheme.Colors.surface)) }).growX().row()
            add(Rw.divider()).growX().height(dp(1f)).row()
        }
        add(ScrollPane(body).apply { setScrollingDisabled(true, false); setFadeScrollBars(true) }).grow().row()

        // 账号卡片
        body.add(Rw.card().apply {
            left()
            add(Rw.badge(Icons.account, RwTheme.Colors.badgeFg[0], RwTheme.Colors.badgeBg[0], 44f, small = false)).size(dp(38f)).padRight(dp(9f))
            add(Table().left().apply {
                add(Label("").apply { update { setText(if (account.loggedIn) "已登录 NewAPI" else "未登录") } }).left().row()
                add(Rw.caption("").apply { setWrap(true); update { setText(if (account.loggedIn) "点「余额」查看额度" else "登录后自动获取专用令牌") } }).left().growX()
            }).growX().minWidth(0f)
            add(Rw.primary("") { toggleLogin() }.apply { update { setText(if (account.loggedIn) "退出" else "登录") } }).height(dp(32f)).padLeft(dp(6f))
        }).growX().row()

        section(body, "线路") { box ->
            item(box, "默认模型", { service.settings.chatModel.ifBlank { "未选择" } }) { pickModel(chat = true) }
            item(box, "生图模型", { service.settings.imageModel.ifBlank { "未选择" } }) { pickModel(chat = false) }
            item(box, "向量模型", { service.settings.embeddingModel.ifBlank { "未选择" } }, hint = "用于长期记忆和语义检索") { pickEmbedding() }
            item(box, "余额", { "" }, last = true) { run("查询中…") { account.info().let { i -> "${i.name}：${i.balance}" } } }
        }

        postTheme?.let { post ->
            section(body, "外观") { box ->
                val modes = RwTheme.Mode.entries
                box.add(Table().left().apply {
                    margin(dp(9f), dp(12f), dp(9f), dp(12f))
                    add(Rw.segmented(modes.map { it.label }, { modes.indexOf(RwTheme.mode) }) { i -> post(SetThemeMode(modes[i])) })
                }).growX()
            }
        }
    }

    private fun toggleLogin() = run("正在登录…") {
        if (account.loggedIn) {
            account.logout()
            "已退出"
        } else {
            account.login()
            service.token = account.ensureRelayKey()
            "登录成功，已获取专用令牌"
        }
    }

    private fun pickModel(chat: Boolean) = run("正在拉取模型列表…") {
        val token = service.token ?: error("请先登录 NewAPI")
        val c = catalog ?: catalogClient.fetch(token, service.settings.baseUrl).also { catalog = it }
        val models = (if (chat) c.chat else c.image).map { it.id }
        check(models.isNotEmpty()) { if (chat) "没有可用的对话模型" else "没有可用的生图模型" }
        val i = Dialogs.choose(if (chat) "选择对话模型" else "选择生图模型", null, models, highlight = -1)
        if (i < 0) return@run null
        service.updateSettings { s -> if (chat) s.copy(chatModel = models[i]) else s.copy(imageModel = models[i]) }
        "已选择 ${models[i]}"
    }

    private fun pickEmbedding() = run("正在拉取模型列表…") {
        val token = service.token ?: error("请先登录 NewAPI")
        val c = catalog ?: catalogClient.fetch(token, service.settings.baseUrl).also { catalog = it }
        val models = c.embedding.map { it.id }
        check(models.isNotEmpty()) { "没有可用的向量模型" }
        val options = models + "关闭"
        val i = Dialogs.choose("选择向量模型", "用于${AppBrand.identity.assistant}的长期记忆和项目文件语义检索", options, highlight = -1)
        if (i < 0) return@run null
        val picked = models.getOrNull(i).orEmpty()
        service.updateSettings { s -> s.copy(embeddingModel = picked) }
        if (picked.isEmpty()) "已关闭" else "已选择 $picked"
    }

    private fun run(progress: String, block: suspend () -> String?) {
        Toasts.show(progress)
        scope.launch {
            val result = runCatching { block() }
            withContext(ArcDispatcher) {
                result.fold({ msg -> msg?.let(Toasts::show) }, { e -> Toasts.show(e.message ?: "操作失败") })
            }
        }
    }

    private fun section(into: Table, title: String, build: (Table) -> Unit) {
        into.add(Rw.section(title)).left().padTop(dp(12f)).padBottom(dp(6f)).padLeft(dp(3f)).row()
        val box = Table(RwTheme.card()).top().left()
        build(box)
        into.add(box).growX().row()
    }

    /** 一行设置：左侧名称（可带说明），右侧当前值和箭头，整行可点。按钮名为名称，验收脚本按它点击。 */
    private fun item(into: Table, label: String, value: () -> String, hint: String? = null, last: Boolean = false, onClick: () -> Unit) {
        into.add(arc.scene.ui.Button(arc.scene.ui.Button.ButtonStyle().apply { down = RwTheme.solid(RwTheme.Colors.surface2) }).apply {
            name = label
            left()
            margin(dp(9f), dp(12f), dp(9f), dp(9f))
            add(Table().left().apply {
                add(Label(label)).left().row()
                if (hint != null) add(Rw.caption(hint)).left()
            })
            add(Label("", RwTheme.Styles.caption).apply {
                setEllipsis(true)
                setAlignment(arc.util.Align.right)
                update { setText(value()) }
            }).growX().minWidth(0f).padLeft(dp(9f))
            add(Label(Icons.chevronRight.toString(), Label.LabelStyle(RwTheme.iconsSmall, RwTheme.Colors.muted))).padLeft(dp(3f))
            clicked(onClick)
        }).growX().row()
        if (!last) into.add(Rw.divider()).growX().height(dp(1f)).row()
    }

}
