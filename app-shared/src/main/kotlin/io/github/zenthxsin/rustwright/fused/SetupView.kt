package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.math.Interp
import arc.math.Smooth
import arc.math.geom.Vec2
import arc.scene.Element
import arc.scene.event.Touchable
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import arc.util.Align
import io.github.zenthxsin.rustwright.ui.designsystem.Lucide
import io.github.zenthxsin.rustwright.ui.designsystem.BackHandler
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.Spinner
import io.github.zenthxsin.rustwright.ui.kit.Tap

/** 首次启动的环境准备状态。 */
interface SetupState {
    val ready: Boolean
    val running: Boolean
    val steps: List<String>
    /** 当前步骤序号（-1 = 还没开始）。 */
    val stepIndex: Int
    val progress: Float
    val detail: String
    val error: String?
    fun failureReport(): String = error.orEmpty()
    fun start()
}

/**
 * 首次启动页（原型 Setup）：先请用户打开后台运行要的权限（有没开的才显示这一步），
 * 点「开始准备」后自动装，进度条 + 五步清单；可以「后台继续」先进去，装好后自动淡出并弹通知。
 */
class SetupView(
    private val state: SetupState,
    private val permissions: () -> List<io.github.zenthxsin.rustwright.core.runtime.KeepAliveItem> = { emptyList() },
    private val openPermission: (String) -> Unit = {},
    private val onFailure: (String) -> Unit = {},
    private val onDone: (background: Boolean) -> Unit,
) : Table(K.fill(K.bg, 0f)), BackHandler {
    private val bar = Bar(0f, K.accent, K.surface3)
    private val pct = K.text("0%", 13f, K.subtle, lh = 13f)
    private val eta = K.text("", 13f, K.subtle, lh = 13f)
    private val list = Table().left()
    private val contentScroll = ScrollPane(null as Element?).apply { setScrollingDisabledX(true); setOverscroll(false, false) }
    private var activeDetail: Element? = null
    private val shown = Smooth(1f, 0.45f, Interp.standard)
    private var lastSig = ""
    private var leaving = false
    // 原型：进度条 width 0.2s linear
    private val shownProgress = Smooth(0f, 0.2f, Interp.linear)
    private val primaryLabel = K.text("后台继续，先进去看看", 17f, K.onLight, 600).apply {
        setWrap(true)
        setAlignment(Align.center)
    }
    private val primary = Tap(K.light, 26f) {
        if (state.error == null) leave(true) else {
            val report = state.failureReport()
            leave(true)
            onFailure(report)
        }
    }.apply {
        name = "后台继续，先进去看看"
        margin(dp(12f), dp(14f), dp(12f), dp(14f))
        add(primaryLabel).growX().minWidth(0f)
        minH = dp(52f)
    }

    /** 权限这一步：没开的权限列表；点「开始准备」后为 null，进入安装。 */
    private var permStep: Table? = null
    private val permList = Table()
    private var permSig = ""
    private var permCheckAt = 0f

    init {
        touchable = Touchable.enabled
        if (permissions().any { it.granted == false }) showPermissions() else showInstall()
    }

    /** 第一步：开始准备环境前，把后台运行要的权限一次说清楚（回来时自动刷新状态）。 */
    private fun showPermissions() {
        val col = Table().left()
        col.margin(dp(24f), dp(28f), dp(24f), dp(28f))
        col.add(io.github.zenthxsin.rustwright.ui.kit.AnvilLogo(44f)).left().row()
        col.add(K.serif(io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.name, 36f)).left().padTop(dp(14f)).row()
        col.add(K.para("开始前先打开这几项。${AppBrand.identity.assistant}干活时你可以切走去做别的，手机不会在后台把任务停掉。", 15f, K.muted, lh = 24f)).growX().left().padTop(dp(14f)).row()
        col.add(permList).growX().padTop(dp(28f)).row()
        rebuildPermissions()
        val start = Tap(K.light, 26f) { showInstall() }
        start.name = "开始准备"
        start.add(K.text("开始准备", 17f, K.onLight, 600))
        start.minH = dp(52f)
        val step = Table()
        step.add(ScrollPane(col).apply { setScrollingDisabledX(true); setOverscroll(false, false) }).grow().row()
        step.add(start).growX().pad(0f, dp(28f), 0f, dp(28f)).row()
        step.add(K.para("没打开的以后也能在「设置 → 后台运行」里打开", 12f, K.subtle, lh = 18f).apply { setAlignment(Align.center) })
            .growX().pad(dp(14f), dp(28f), dp(28f), dp(28f))
        permStep = step
        add(step).grow()
    }

    private fun rebuildPermissions() {
        val items = permissions()
        val sig = items.joinToString { "${it.id}${it.granted}" }
        if (sig == permSig) return
        permSig = sig
        permList.clearChildren()
        val g = io.github.zenthxsin.rustwright.ui.kit.ListGroup()
        for (it in items) {
            val state = when (it.granted) { true -> "已开启"; false -> "去开启"; null -> "去设置" }
            g.row(io.github.zenthxsin.rustwright.ui.kit.listRow(it.title, it.description, null, trailing = state, chevron = it.granted != true, minHeight = 64f) {
                if (it.granted != true) openPermission(it.id)
            }.apply { name = "keepalive-" + it.id })
        }
        permList.add(g).growX()
    }

    /** 第二步：准备环境。 */
    private fun showInstall() {
        permStep?.remove()
        permStep = null
        clearChildren()
        val col = Table().left()
        col.margin(dp(24f), dp(28f), dp(24f), dp(28f))
        col.add(io.github.zenthxsin.rustwright.ui.kit.AnvilLogo(44f)).left().row()
        col.add(K.serif(io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.name, 36f)).left().padTop(dp(14f)).row()
        col.add(K.para("正在准备工作环境，只有第一次需要。你可以先去逛逛，装好后${AppBrand.identity.assistant}会自动可用。", 15f, K.muted, lh = 24f)).growX().left().padTop(dp(14f)).row()
        col.add(bar).growX().height(dp(6f)).padTop(dp(32f)).row()
        val nums = Table()
        nums.add(pct).left().growX()
        nums.add(eta)
        col.add(nums).growX().padTop(dp(14f)).row()
        col.add(list).growX().padTop(dp(32f))
        contentScroll.setWidget(col)
        add(contentScroll).grow().row()
        add(primary).growX().pad(0f, dp(28f), 0f, dp(28f)).row()
        add(K.para("至少预留 2 GB 空间 · 中途退出下次会接着装", 12f, K.subtle, lh = 18f).apply { setAlignment(Align.center) })
            .growX().pad(dp(14f), dp(28f), dp(28f), dp(28f))
        state.start()
    }

    private fun leave(background: Boolean) {
        if (leaving) return
        leaving = true
        // 原型：启动页直接卸载，主界面 rwfade 入场（见 FusedShell）
        onDone(background)
        remove()
    }

    override fun onBackRequested(leave: () -> Unit): Boolean {
        leave(true)
        return true
    }

    override fun act(delta: Float) {
        super.act(delta)
        if (permStep != null) {
            // 从系统设置回来后刷新权限状态（一秒查一次）
            permCheckAt += delta
            if (permCheckAt > 1f) { permCheckAt = 0f; rebuildPermissions() }
            return
        }
        shown.update(delta)
        color.a = shown.get()
        if (leaving && shown.get() <= 0.01f) {
            remove()
            return
        }
        if (state.ready && !leaving) leave(false)
        shownProgress.to(if (state.ready) 1f else state.progress).update(delta)
        val p = shownProgress.get()
        barValue(p)
        pct.setText("${(p * 100).toInt()}%")
        eta.setText(state.error?.let { "安装失败" } ?: if (state.ready) "完成" else "约 ${((1f - p) * 6f).toInt().coerceAtLeast(1)} 分钟")
        eta.color.set(if (state.error == null) K.subtle else K.danger)
        val buttonText = if (state.error == null) "后台继续，先进去看看" else "复制报错并进入终端"
        if (primary.name != buttonText) { primary.name = buttonText; primaryLabel.setText(buttonText) }
        val sig = "${state.stepIndex}|${state.detail}|${state.error}"
        if (sig != lastSig) {
            lastSig = sig
            rebuildList()
            // Large text may move the active step below the fixed action button.
            // Follow stage changes once; subsequent user scrolling remains untouched.
            contentScroll.validate()
            list.validate()
            activeDetail?.let { detail ->
                val at = detail.localToAscendantCoordinates(contentScroll.widget, Vec2())
                contentScroll.scrollTo(at.x, at.y, detail.width, detail.height, false, false)
                contentScroll.updateVisualScroll()
            }
        }
    }

    private fun barValue(v: Float) {
        bar.value = v
    }

    private fun rebuildList() {
        list.clearChildren()
        activeDetail = null
        state.steps.forEachIndexed { i, label ->
            val done = state.ready || i < state.stepIndex
            val active = !done && i == state.stepIndex
            val icon = Table()
            when {
                done -> icon.add(K.svg(io.github.zenthxsin.rustwright.ui.kit.PI.check, 20f, K.green, 2.2f))
                active && state.error == null -> icon.add(Spinner(20f))
                active -> icon.add(K.icon(Lucide.circleAlert, 20f, K.danger))
                else -> icon.add(Table(K.fill(K.inputBorder, 4f))).size(dp(8f))
            }
            list.add(icon).size(dp(22f)).padRight(dp(14f))
            val title = K.para(label, 15f, if (done || active) K.text else K.subtle, lh = 22f)
            list.add(title).growX().minWidth(0f).left()
            if (active) activeDetail = title
            list.row()
            if (active && state.detail.isNotBlank()) {
                list.add().width(dp(36f))
                val description = K.para(state.detail, 13f, if (state.error == null) K.subtle else K.danger, lh = 20f)
                activeDetail = description
                list.add(description)
                    .growX().minWidth(0f).left().padTop(dp(4f)).row()
            }
            if (i < state.steps.lastIndex) list.add().colspan(2).height(dp(18f)).row()
        }
    }
}
