package io.github.zenthxsin.rustwright.fused

import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.account.BenefitWindow
import io.github.zenthxsin.rustwright.core.account.NewApiBenefits
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.chip
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.listRow
import arc.graphics.Color
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.NumberFormat

/** 仅显示服务端快照；本机时钟不会恢复额度或决定可用资格。 */
data class BenefitsState(
    val loggedIn: Boolean = false,
    val loading: Boolean = false,
    val data: NewApiBenefits? = null,
    val failed: Boolean = false,
    val accountSubject: String? = null,
    val receivedNanos: Long = System.nanoTime(),
    val errorMessage: String? = null,
) {
    fun summary(): String = when {
        !loggedIn -> "登录后查看"
        loading -> "正在读取福利状态…"
        failed -> errorMessage ?: "暂时读不到福利状态，请重试"
        data == null -> "尚未读取福利状态"
        !data.enabled -> "福利尚未开放"
        data.balance?.needsReview == true -> "用量待核对，免费调用已暂停"
        !data.eligible -> when (data.eligibilityReason) {
            "login_required", "unauthenticated" -> "请先登录 NewAPI"
            "account_disabled" -> "NewAPI 账号当前不可用"
            "authorization_expired" -> "登录已过期，请重新登录 NewAPI"
            "review_required" -> "用量待核对，免费调用已暂停"
            "not_configured", "disabled" -> "福利尚未开放"
            else -> "当前账号暂时无法使用福利"
        }
        !data.textAvailable && !data.generationAvailable -> "当前福利额度已用完"
        else -> "NewAPI 登录福利"
    }

    companion object { val SignedOut = BenefitsState() }
}

/**
 * 订阅计划（在建）：现在只有登录 NewAPI 就有的「免费计划」，付费计划还没开放。
 * 额度卡和用量弹层同一种样子（已使用 % + 进度条 + 何时重置）；所有数量直接来自 NewAPI 的独立福利账本。
 */
class BenefitsPage(private val host: FusedHost) : Table() {
    private val content = Table().top().left()
    private var shown: BenefitsState? = null

    init {
        top()
        content.margin(dp(8f), dp(20f), dp(28f), dp(20f))
        add(ScrollPane(content).apply { setScrollingDisabledX(true); setOverscroll(false, true) }).grow()
        host.refreshBenefits()
        update {
            val state = host.benefitsState()
            if (state != shown) { shown = state; rebuild(state) }
        }
        shown = host.benefitsState()
        rebuild(shown!!)
    }

    private fun h3(text: String) = content.add(K.text(text, 15f, K.muted, 500)).left().pad(dp(24f), dp(4f), dp(10f), dp(4f)).row()

    private fun rebuild(state: BenefitsState) {
        content.clearChildren()
        val data = state.data.takeIf { state.loggedIn && !state.failed && !state.loading }
        val active = data?.enabled == true && data.eligible

        // 当前计划
        val hero = Table(K.fill(K.surface, 14f)).left()
        hero.margin(dp(18f))
        hero.add(K.text("当前计划", 13f, K.subtle, lh = 13f)).left().row()
        val planRow = Table()
        planRow.add(K.text("免费计划", 24f, K.text, 600, 28f)).left()
        planRow.add(Tag(if (active) "使用中" else "未开通", if (active) K.green else K.subtle)).padLeft(dp(10f))
        hero.add(planRow).left().padTop(dp(8f)).row()
        val line = if (active && state.summary() == "NewAPI 登录福利") "登录 NewAPI 即可使用。用量由 NewAPI 结算，思考 token 不计入额度。" else state.summary()
        hero.add(K.para(line, 14f, K.subtle, lh = 20f)).growX().left().padTop(dp(10f)).row()
        content.add(hero).growX().row()

        data?.balance?.let { b ->
            h3("本期额度")
            content.add(Sheets.quotaCard("5 小时对话", b.fiveHours, state, null)).growX().row()
            content.add(Sheets.quotaCard("7 天对话", b.sevenDays, state, null)).growX().padTop(dp(10f)).row()
            content.add(Sheets.quotaCard("每日生成", b.generation, state, b.dayTimezone,
                "今日已使用 ${number(b.generation.used)} 次")).growX().padTop(dp(10f)).row()
            if (b.pendingText > 0 || b.pendingGeneration > 0) {
                content.add(K.para("正在结算：对话 ${number(b.pendingText)} 笔 · 生成 ${number(b.pendingGeneration)} 笔，结算后更新。", 13f, K.subtle, lh = 19f))
                    .growX().left().pad(dp(10f), dp(4f), 0f, dp(4f)).row()
            }
        }

        if (active) {
            h3("包含")
            val g = ListGroup()
            val models = data!!.textModels.map(NewApiBenefits::displayModel)
            if (models.isNotEmpty()) g.row(listRow("对话模型", models.joinToString("、"), PI.chat, K.muted, minHeight = 60f))
            data.imageModel?.let { g.row(listRow("生图模型", NewApiBenefits.displayModel(it), PI.img, K.muted, minHeight = 60f)) }
            g.row(listRow("生成工具共享每日次数", "额度用完会停下，等恢复或换其他模型", PI.layers, K.muted, minHeight = 60f))
            content.add(g).growX().row()
        }

        // 付费计划：在建
        h3("更多计划")
        val pro = Table(K.card(K.surface, 14f)).left()
        pro.margin(dp(18f))
        val head = Table()
        head.add(K.text("专业版", 18f, K.text, 600, 20f)).left()
        head.add(Tag("在建", K.c("E9A23B"))).padLeft(dp(10f))
        head.add().growX()
        pro.add(head).growX().row()
        pro.add(K.para("更高的对话和生图额度、更多模型、优先线路。正在搭建，开放后在这里订阅。", 14f, K.subtle, lh = 20f))
            .growX().left().padTop(dp(10f)).row()
        content.add(pro).growX().row()

        content.add(chip(if (state.loading) "读取中…" else "刷新", false, 42f) {
            if (!host.benefitsState().loading) host.refreshBenefits()
        }.apply { name = "刷新订阅状态" }).left().padTop(dp(22f)).row()
    }

    /** 计划名后面的小标签：浅色底 + 同色字。 */
    private class Tag(text: String, color: Color) : Table() {
        init {
            background(K.fill(Color(color).a(0.16f), 9f))
            margin(dp(3f), dp(9f), dp(4f), dp(9f))
            add(K.text(text, 12f, color, 500, 12f))
        }
    }

    companion object {
        private fun number(value: Long): String = NumberFormat.getIntegerInstance(java.util.Locale.US).format(value)

        /** 用量弹层用的一行：「约 4 小时 8 分钟后重置」，不带日期和时区。 */
        internal fun resetShort(window: BenefitWindow, state: BenefitsState, timezone: String?): String {
            if (window.startsAt == 0L) return if (timezone == null) "首次使用后开始计时" else "首次生成后开始计时"
            return resetText(window, state, timezone).substringBefore(" · ").substringBefore("（")
        }

        internal fun resetText(window: BenefitWindow, state: BenefitsState, timezone: String?): String {
            if (window.startsAt == 0L) return if (timezone == null) "尚未开始 · 首次使用后开始计时" else "尚未开始 · 首次生成后计入当日（$timezone）"
            val zone = timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            val time = runCatching { DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone).format(Instant.ofEpochSecond(window.resetsAt)) }.getOrDefault("服务端指定时间")
            val suffix = timezone?.let { "（$it）" }.orEmpty()
            val serverTime = state.data?.serverTime ?: return "$time 重置$suffix"
            val elapsed = ((System.nanoTime() - state.receivedNanos).coerceAtLeast(0L) / 1_000_000_000L)
            val remaining = (window.resetsAt - serverTime - elapsed).coerceAtLeast(0L)
            if (remaining == 0L) return "已到重置时间 · 请刷新确认额度$suffix"
            val minutes = (remaining + 59) / 60
            val wait = if (minutes >= 1440) "${minutes / 1440} 天 ${minutes % 1440 / 60} 小时" else if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分钟" else "$minutes 分钟"
            return "约 ${wait}后重置 · $time$suffix"
        }
    }
}
