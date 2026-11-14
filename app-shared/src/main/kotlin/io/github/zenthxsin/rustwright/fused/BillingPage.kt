package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.OwnHeader
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.PageBar
import io.github.zenthxsin.rustwright.ui.kit.listRow
import io.github.zenthxsin.rustwright.ui.kit.pageBar

/**
 * 充值与账单：余额大字一眼看到，下面「去充值」和「使用记录」（都在浏览器打开中转站对应页面）。
 * 用量、免费福利在设置里各有一行，这里不重复。
 */
internal class BillingPage(
    private val s: FusedShell,
    private val loggedIn: () -> Boolean,
    private val topup: () -> Unit,
    private val logs: () -> Unit,
    private val login: () -> Unit,
) : Table(), OwnHeader {
    private val bar = PageBar("充值与账单")
    private val amount = K.text("", 34f, K.text, 600, 40f)
    private val hint = K.para("", 14f, K.subtle, lh = 21f)

    override fun bindBack(back: () -> Unit) = bar.bindBack(back)

    init {
        top()
        pageBar(bar)
        val col = Table().top()
        col.margin(dp(8f), dp(16f), dp(28f), dp(16f))

        val card = Table(K.fill(K.surface, 14f))
        card.margin(dp(18f))
        card.add(K.text("账户余额", 14f, K.muted, lh = 14f)).left().row()
        card.add(amount).left().padTop(dp(10f)).row()
        card.add(hint).growX().left().padTop(dp(10f))
        col.add(card).growX().row()

        val g = ListGroup()
        g.row(listRow("去充值", "在浏览器里打开中转站充值页", PI.wallet, chevron = true, minHeight = 64f) { if (loggedIn()) topup() else login() })
        g.row(listRow("使用记录", "每次调用的模型、用量和扣费", PI.clock, chevron = true, minHeight = 64f) { if (loggedIn()) logs() else login() })
        col.add(g).growX().padTop(dp(18f)).row()
        add(ScrollPane(col).apply { setScrollingDisabledX(true); setOverscroll(false, true) }).grow()
    }

    override fun act(delta: Float) {
        super.act(delta)
        val b = s.host.balance()
        val text = when {
            !loggedIn() -> "—"
            b == null -> "读取中…"
            b.startsWith("¥") || b.startsWith("$") -> b
            else -> "¥$b"
        }
        if (amount.text.toString() != text) amount.setText(text)
        val h = if (loggedIn()) "按线路实际消耗扣费；余额不足时${AppBrand.identity.assistant}会先停下来问你。" else "登录 NewAPI 后显示余额。点「去充值」先登录。"
        if (hint.text.toString() != h) hint.setText(h)
    }
}
