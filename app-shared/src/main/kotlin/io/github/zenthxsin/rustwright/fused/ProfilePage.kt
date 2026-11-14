package io.github.zenthxsin.rustwright.fused

import arc.Core
import arc.graphics.Color
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.account.NewApiAccount
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.OwnHeader
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.PageBar
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.pageBar
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import io.github.zenthxsin.rustwright.ui.widgets.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 个人资料 = 账号详情（参考 Claude App 的个人资料页）：
 * - 居中的大头像、名字、一句身份说明；没登录时下面一个「登录 NewAPI」主按钮
 * - 「账户」一组：余额、免费额度、用量
 * - 登录后最下面「退出登录」
 * 模型选择统一放在设置 →「模型」，这里不再重复；线路和令牌由登录自动配置。
 */
internal class ProfilePage(
    private val s: FusedShell,
    private val account: NewApiAccount,
    private val scope: CoroutineScope,
    /** 打开中转站充值页。 */
    private val topup: () -> Unit,
    /** 登录（拿专用令牌），成功返回提示文字。 */
    private val loginAction: suspend () -> Unit,
    /** 登录 / 退出之后（重启 Codex、刷新账号信息）。 */
    private val onChanged: () -> Unit,
) : Table(), OwnHeader {
    private val bar = PageBar("个人资料")
    private val col = Table().top()
    private var shownKey = ""
    private var busy = false

    override fun bindBack(back: () -> Unit) = bar.bindBack(back)

    init {
        top()
        pageBar(bar)
        col.margin(dp(12f), dp(16f), dp(28f), dp(16f))
        add(ScrollPane(col).apply { setScrollingDisabledX(true); setOverscroll(false, true) }).grow()
        rebuild()
    }

    private fun key() = listOf(account.loggedIn, s.host.accountEmail, s.host.balance(), s.host.benefitsState().summary(), busy).joinToString("|")

    override fun act(delta: Float) {
        super.act(delta)
        if (key() != shownKey) rebuild()
    }

    private fun rebuild() {
        shownKey = key()
        col.clearChildren()
        val signedIn = account.loggedIn
        val name = s.host.accountEmail

        // 头像 + 名字，居中
        val hero = Table()
        val avatar = Table(K.fill(if (signedIn) K.accent else K.surface3, 36f))
        if (signedIn && name.isNotBlank()) avatar.add(K.text(name.take(1).uppercase(), 30f, Color.white, 600))
        else avatar.add(K.svg(PI.user, 32f, K.muted, 1.6f))
        hero.add(avatar).size(dp(72f)).row()
        hero.add(K.text(if (signedIn) name.ifBlank { "NewAPI 账号" } else "还没有登录", 20f, K.text, 600, 22f).apply { setEllipsis(true) })
            .maxWidth(dp(300f)).padTop(dp(14f)).row()
        hero.add(K.text(if (signedIn) "NewAPI 账号 · 线路已自动配置" else "登录后领免费额度，线路自动配置", 13f, K.subtle, lh = 13f)).padTop(dp(8f)).row()
        if (!signedIn) {
            val go = Tap(K.light, 12f) { login() }
            go.name = "登录 NewAPI"
            go.add(K.text(if (busy) "正在登录…" else "登录 NewAPI", 16f, K.onLight, 600))
            hero.add(go).width(dp(220f)).height(dp(46f)).padTop(dp(18f))
        }
        col.add(hero).growX().padTop(dp(8f)).padBottom(dp(26f)).row()

        // 账户
        col.add(K.text("账户", 14f, K.muted, 500)).left().pad(0f, dp(4f), dp(8f), dp(4f)).row()
        val g = ListGroup()
        val b = s.host.balance()
        g.row(row("余额", if (!signedIn) "—" else b?.let { if (it.startsWith("¥") || it.startsWith("$")) it else "¥$it" } ?: "读取中…") { if (signedIn) topup() else login() })
        g.row(row("订阅计划", s.host.benefitsState().summary()) { s.openTool("订阅计划", BenefitsPage(s.host), s.page) })
        g.row(row("用量", "") { Sheets.usage(s) })
        col.add(g).growX().row()

        if (signedIn) {
            val out = ListGroup()
            val r = Tap(K.surface, 0f) { logout() }
            r.name = "退出登录"
            r.margin(0f, dp(16f), 0f, dp(16f))
            r.add(K.text("退出登录", 16f, K.danger, lh = 16f)).growX().left()
            r.minH = dp(54f)
            out.row(r)
            col.add(out).growX().padTop(dp(24f)).row()
        }
    }

    /** 一行：名称在左，当前值在右（过长省略），右箭头。 */
    private fun row(title: String, value: String, onClick: () -> Unit): Tap {
        val r = Tap(K.surface, 0f, onClick)
        r.name = title
        r.margin(0f, dp(16f), 0f, dp(14f))
        r.add(K.text(title, 16f, K.text, lh = 16f)).left().padRight(dp(12f))
        r.add(K.text(value, 14f, K.muted, lh = 14f).apply { setEllipsis(true); setAlignment(arc.util.Align.right) }).growX().minWidth(0f).right()
        r.add(K.svg(PI.chevronRight, 16f, K.subtle, 2f)).padLeft(dp(6f))
        r.minH = dp(54f)
        return r
    }

    private fun login() {
        if (busy) return
        busy = true
        Toasts.show("正在打开登录页…")
        scope.launch {
            val r = runCatching { loginAction() }
            Core.app.post {
                busy = false
                r.fold({ Toasts.show("登录成功"); onChanged() }, { Toasts.show(it.message ?: "登录失败") })
                rebuild()
            }
        }
    }

    private fun logout() = scope.launch {
        val i = Dialogs.choose("退出登录？", "退出后免费额度和专用线路不可用，可以随时再登录。", listOf("取消", "退出登录"), highlight = 1)
        if (i != 1) return@launch
        runCatching { account.logout() }
        Core.app.post { Toasts.show("已退出登录"); onChanged(); rebuild() }
    }
}
