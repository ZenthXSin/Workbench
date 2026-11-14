package io.github.zenthxsin.rustwright

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.scene.ui.Label
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.agent.AgentService
import io.github.zenthxsin.rustwright.core.runtime.RwContext
import io.github.zenthxsin.rustwright.ui.designsystem.NavigateTo
import io.github.zenthxsin.rustwright.core.runtime.RwModule
import io.github.zenthxsin.rustwright.core.update.UpdateCheckResult
import io.github.zenthxsin.rustwright.core.update.Updater
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import io.github.zenthxsin.rustwright.ui.designsystem.Rw
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.designsystem.Icons
import io.github.zenthxsin.rustwright.ui.designsystem.Screen
import io.github.zenthxsin.rustwright.ui.designsystem.ScreenRegistry
import io.github.zenthxsin.rustwright.ui.designsystem.SetThemeMode
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import io.github.zenthxsin.rustwright.ui.widgets.MarkdownView
import io.github.zenthxsin.rustwright.ui.widgets.Toasts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 首页：概览、工作台与快捷入口，检查更新。[updateUrl] 为空时提示未配置更新地址。 */
class HomeModule(private val updateUrl: String = "") : RwModule {
    override val id = "home"

    override fun init(ctx: RwContext) {
        ctx.service<ScreenRegistry>().register(Screen("home", "首页", order = 0, icon = Icons.home) {
            val body = Table().top().left().apply { margin(0f, dp(RwTheme.Dimens.gutter), dp(20f), dp(RwTheme.Dimens.gutter)) }
            body.add(Table().left().apply {
                add(Label(io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.name, RwTheme.Styles.title).apply { setFontScale(1.4f) }).left().row()
                add(Rw.caption(io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.description)).left().padTop(dp(2f))
            }).growX().left().padTop(dp(28f)).padBottom(dp(16f)).row()
            // 问助手：像输入框的入口，点了进工作台
            body.add(Rw.listItem("问${AppBrand.identity.assistant}…", icon = Icons.agent, liveSubtitle = { agentSummary(ctx) }) { ctx.events.post(NavigateTo("agent")) }
                .apply { style = arc.scene.ui.Button.ButtonStyle().apply { up = RwTheme.card(radiusDp = 14); down = RwTheme.card(RwTheme.Colors.surface2, radiusDp = 14) } })
                .growX().padBottom(dp(18f)).row()
            body.add(Rw.section("工作台")).left().padLeft(dp(4f)).padBottom(dp(6f)).row()
            val tools = ctx.extensions.all(io.github.zenthxsin.rustwright.ui.kit.UiPoints.TOOLS).filter { it.group != null }
            if (tools.isNotEmpty()) body.add(Rw.group(*tools.map { tool ->
                entry(ctx, tool.name, tool.id, Icons.extension) { tool.desc() }
            }.toTypedArray())).growX().row()

            body.add(Rw.section("应用")).left().padLeft(dp(4f)).padTop(dp(18f)).padBottom(dp(6f)).row()
            body.add(Rw.group(
                Rw.listItem("外观", icon = Icons.palette, liveSubtitle = { RwTheme.mode.label }) { chooseTheme(ctx) },
                Rw.listItem("使用说明", icon = Icons.help) { markdownDialog(ctx, "使用说明", Guide.MARKDOWN) },
                Rw.listItem("检查更新", "当前版本 ${ctx.platform.appVersion}", icon = Icons.download) { checkUpdate(ctx) },
            )).growX().row()
            ScrollPane(body).apply { setScrollingDisabled(true, false); setFadeScrollBars(true) }
        })
    }

    /** 首页入口：图标 + 标题 + 实时摘要，点击跳到对应页面。 */
    private fun entry(ctx: RwContext, title: String, page: String, icon: Char, summary: () -> String) =
        Rw.listItem(title, icon = icon, iconColor = RwTheme.Colors.text, liveSubtitle = summary) { ctx.events.post(NavigateTo(page)) }

    private fun agentSummary(ctx: RwContext): String {
        val agent = ctx.services.getOrNull<AgentService>() ?: return ""
        if (agent.token.isNullOrBlank()) return "还没登录，点这里登录 NewAPI"
        val wb = ctx.services.getOrNull<io.github.zenthxsin.rustwright.core.codex.CodexWorkbench>()
        val last = wb?.conversations?.maxByOrNull { it.updatedAt }
        return last?.let { "继续：${it.title}" } ?: "本机 Codex · ${agent.settings.chatModel.ifBlank { "未选模型" }}"
    }

    private fun chooseTheme(ctx: RwContext) {
        ctx.scope.launch {
            val modes = RwTheme.Mode.entries
            val i = Dialogs.choose("外观", null, modes.map { it.label }, highlight = modes.indexOf(RwTheme.mode))
            if (i >= 0) ctx.events.post(SetThemeMode(modes[i]))
        }
    }

    private fun markdownDialog(ctx: RwContext, title: String, markdown: String) {
        ctx.scope.launch {
            Dialogs.choose(title, null, listOf("关闭"), content = {
                ScrollPane(MarkdownView().apply { setMarkdown(markdown) }).apply { setScrollingDisabled(true, false) }
            })
        }
    }

    private fun checkUpdate(ctx: RwContext) {
        val url = updateUrl
        if (url.isBlank()) return Toasts.show("此版本未配置更新地址")
        ctx.scope.launch {
            val updater = Updater(url, File(ctx.platform.cacheDir, "update"))
            val result = withContext(Dispatchers.IO) { runCatching { updater.check(ctx.platform.appVersionCode) } }
            val r = result.getOrElse { e -> return@launch withContext(ArcDispatcher) { Toasts.show(e.message ?: "检查更新失败") } }
            if (r !is UpdateCheckResult.Available) return@launch withContext(ArcDispatcher) { Toasts.show("已是最新版本") }
            val info = r.update
            val notes = (listOf(info.title) + info.notes.map { "· $it" }).filter { it.isNotBlank() }.joinToString("\n")
            if (!Dialogs.confirm("发现新版本 ${info.versionName}", notes.ifBlank { "是否下载并安装？" }, ok = "下载安装")) return@launch
            withContext(ArcDispatcher) { Toasts.show("正在下载…") }
            val apk = withContext(Dispatchers.IO) { runCatching { updater.download(info) } }
            apk.fold(
                { ctx.platform.installUpdate(it, info.versionCode) },
                { e -> withContext(ArcDispatcher) { Toasts.show("下载失败：${e.message}") } },
            )
        }
    }
}
