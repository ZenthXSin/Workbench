package io.github.zenthxsin.rustwright.ui.kit

import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Backdrop
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Shapes
import arc.math.Interp
import arc.math.Smooth
import arc.scene.Element
import arc.scene.event.Touchable
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Switcher
import arc.scene.ui.layout.Table
import arc.scene.ui.layout.WidgetGroup
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp

/** 全屏半透明遮罩：透明度由 [alpha] 决定，点一下执行 [onTap]。 */
internal class Scrim(private val alpha: () -> Float, onTap: () -> Unit) : Element() {
    init {
        clicked(onTap)
    }

    override fun draw() {
        val a = alpha()
        if (a <= 0.001f) return
        Draw.color(0f, 0f, 0f, 0.55f * a * parentAlpha)
        arc.graphics.g2d.Fill.crect(x, y, width, height)
        Draw.reset()
    }
}

/** 底部弹层里的一页：标题、可选副标题、可选右上角按钮、内容；[height] 为原型里写死的面板高度（dp），null 为按内容。 */
class SheetPage(val title: String, val body: Element, val sub: String? = null, val right: Element? = null, val height: Float? = null)

/**
 * 底部弹层（原型 section）：面板 translateY(105%) ↔ 0（0.3s cubic-bezier(.2,.8,.2,1)），遮罩透明度 0.25s ease；
 * 面板高度按页写死（模型 540、模式 420、附件 380、步骤 560…），两页都写死时高度过渡 0.3s，否则直接跳；最高 86%。
 * 页内前进 / 返回：返回键原地换图标，标题和内容从右 / 左 28px 处滑入淡入（0.3s）。
 */
class SheetHost : WidgetGroup() {
    private val open = Smooth(0f, 0.3f, Interp.standard)
    private val scrimA = Smooth(0f, 0.25f, Interp.ease)
    private val sheetH = Smooth(0f, 0.3f, Interp.standard)
    private val stack = ArrayList<SheetPage>()
    private val titles = Switcher().apply { slide = dp(28f); duration(0.3f) }
    private val bodies = Switcher().apply { slide = dp(28f); duration(0.3f) }
    private val scrim = Scrim({ scrimA.get() }) { close() }
    private val backIcon = SvgIcon(PI.close, 24f, K.text, 1.7f)
    private val back = iconButton(backIcon, 48f) { if (stack.size > 1) pop() else close() }
    private val right = Table()
    private val header = Table()
    private var autoHeight = true

    private val panel = object : Table() {
        override fun drawBackground(x: Float, y: Float) {
            val a = color.a * parentAlpha
            Shapes.shadow(x, y, width, height, dp(22f), 0f, -dp(10f), dp(30f), 0f, Color(0f, 0f, 0f, 0.35f * a))
            Shapes.rect(x, y, width, height, dp(22f), dp(22f), 0f, 0f, Color(K.bg).a(a), 0f, null)
            // 把手 44×5，距顶 10
            Shapes.rect(x + width / 2f - dp(22f), y + height - dp(15f), dp(44f), dp(5f), dp(2.5f), Color(K.inputBorder).a(a))
        }
    }

    init {
        setFillParent(true)
        visible = false
        addChild(scrim)
        addChild(panel)
        panel.touchable = Touchable.enabled
        panel.top()
        // 标题行 padding 8 8 6（把手占 15）
        header.add(back).size(dp(48f)).top()
        header.add(titles).growX().top().padTop(dp(12f))
        header.add(right).size(dp(48f)).top()
        panel.add(header).growX().pad(dp(15f + 8f), dp(8f), dp(6f), dp(8f)).row()
        panel.add(bodies).grow()
    }

    val isOpen get() = stack.isNotEmpty()
    val depth get() = stack.size
    /** 刚从关闭状态打开：这一帧在建内容、排版，不推进动画，否则入场动画会被这一帧的耗时吞掉一截。 */
    private var fresh = false

    /**
     * 预热：在同一帧里打开 [openPage] 建好的弹层、排一次版、立刻收起（不会画出来）。
     * 提前生成字形和图标纹理，第一次真正打开时不卡。
     */
    fun prewarm(openPage: () -> Unit) {
        if (isOpen || visible) return
        openPage()
        validate()
        stack.clear()
        open.snap(0f); scrimA.snap(0f)
        visible = false
        fresh = false
    }

    /** 打开一页（清空之前的页）。 */
    fun open(page: SheetPage) {
        val wasClosed = !visible || open.target() == 0f
        stack.clear()
        stack += page
        show(page, Switcher.NONE)
        if (wasClosed) {
            visible = true
            validateHeight(true)
            open.snap(0f)
            fresh = true
        }
        open.to(1f)
        scrimA.to(1f)
    }

    /** 进入下一级（从右侧滑入）。 */
    fun push(page: SheetPage) {
        if (stack.isEmpty()) return open(page)
        stack += page
        show(page, Switcher.PUSH)
    }

    /** 返回上一级；已经是第一页时关闭。 */
    fun pop() {
        if (stack.size <= 1) return close()
        stack.removeAt(stack.lastIndex)
        show(stack.last(), Switcher.POP)
    }

    /** 回到上一级之后替换当前页（选中后返回上级时用），不做动画。 */
    fun replaceTop(page: SheetPage) {
        if (stack.isEmpty()) return open(page)
        stack[stack.lastIndex] = page
        show(page, Switcher.NONE)
    }

    fun close() {
        stack.clear()
        open.to(0f)
        scrimA.to(0f)
        Core.scene?.setKeyboardFocus(null)
    }

    private fun show(page: SheetPage, mode: Int) {
        backIcon.path = if (stack.size > 1) PI.back else PI.close
        back.name = if (stack.size > 1) "返回" else "关闭"
        right.clearChildren()
        page.right?.let { right.add(it).size(dp(48f)) }
        val t = Table().top()
        t.add(K.text(page.title, 18f, K.text, 600, 26f).apply { setWrap(true); setAlignment(arc.util.Align.center) }).growX().center()
        if (page.sub != null) t.row().add(K.para(page.sub, 14f, K.subtle, lh = 20f).apply { setAlignment(arc.util.Align.center) }).growX().minWidth(0f).padTop(dp(2f)).center()
        titles.show(t, mode)
        val scroll = ScrollPane(page.body)
        scroll.setScrollingDisabledX(true)
        scroll.setOverscroll(false, true)
        val b = Table()
        b.add(scroll).grow().pad(dp(4f), dp(14f), dp(24f), dp(14f))
        bodies.show(b, mode)
        // 高度：两页都写死才过渡
        val fixed = page.height
        val prevAuto = autoHeight
        autoHeight = fixed == null
        if (fixed != null) {
            val h = dp(fixed).coerceAtMost(maxH())
            if (prevAuto || mode == Switcher.NONE && open.target() == 0f) sheetH.snap(h) else sheetH.to(h)
        } else validateHeight(true)
    }

    private fun maxH() = if (height > 0f) height * 0.86f else Float.MAX_VALUE
    private fun sheetWidth() = if (width >= dp(WIDE_SHEET_FROM)) minOf(width, dp(640f)) else width

    private fun validateHeight(snap: Boolean) {
        if (!autoHeight) return
        val page = stack.lastOrNull() ?: return
        // 先按弹层实际内容宽度排一次版再量：折行文字在没定宽时量出来偏矮，打开后排好版又变高，弹层就会猛地长一截
        val contentW = sheetWidth() - dp(28f)
        if (contentW > 0f && page.body.width != contentW) {
            page.body.width = contentW
            page.body.invalidateHierarchy()
        }
        page.body.validate()
        titles.validate()
        val want = (dp(15f + 8f + 6f) + maxOf(dp(48f), titles.prefHeight + dp(12f)) + page.body.prefHeight + dp(4f + 24f)).coerceAtMost(maxH())
        if (snap) sheetH.snap(want) else sheetH.to(want)
    }

    override fun act(delta: Float) {
        super.act(delta)
        if (!visible) return
        // 打开动画进行中直接对齐；打开后内容再变（数据刷新、展开）就用动画过渡，不瞬间跳
        validateHeight(open.get() < 0.05f)
        val d = if (fresh) 0f else delta
        fresh = false
        open.update(d)
        scrimA.update(d)
        sheetH.update(d)
        if (open.get() <= 0.001f && open.target() == 0f && scrimA.get() <= 0.001f) visible = false
        invalidate()
    }

    override fun layout() {
        scrim.setBounds(0f, 0f, width, height)
        val h = sheetH.get()
        // Switcher 的最小高度为 0；先按可用宽度量换行标题，再给标题行保留完整高度。
        // 否则长服务器名切到两行后仍按上一页一行高度摆正文，滚动时会盖住标题。
        // 大屏（平板、电脑）：弹层不再铺满整宽，居中最宽 640dp
        val pw = sheetWidth()
        titles.current()?.let { title ->
            title.setSize((pw - dp(16f + 96f)).coerceAtLeast(0f), title.height)
            title.validate()
            val wanted = title.prefHeight
            header.getCell(titles).height(wanted)
        }
        // translateY(105%)
        panel.setBounds((width - pw) / 2f, -(1f - open.get()) * h * 1.05f, pw, h)
        panel.invalidate()
        panel.validate()
    }

    override fun hit(x: Float, y: Float, touchable: Boolean): Element? =
        if (!visible || open.target() == 0f) null else super.hit(x, y, touchable)
}

/**
 * 侧边栏：遮罩 + 从左侧滑出的面板（宽 320dp）。
 */
class DrawerHost(private val panelContent: Element) : WidgetGroup() {
    // 原型：面板 translateX(-105%) ↔ 0，0.28s cubic-bezier(.2,.8,.2,1)；遮罩 0.25s ease
    private val open = Smooth(0f, 0.28f, Interp.standard)
    private val scrimA = Smooth(0f, 0.25f, Interp.ease)
    private val scrim = Scrim({ scrimA.get() }) { close() }
    private val panel = object : Table() {
        override fun drawBackground(x: Float, y: Float) {
            val a = color.a * parentAlpha
            Shapes.shadow(x, y, width, height, 0f, dp(8f), 0f, dp(30f), 0f, Color(0f, 0f, 0f, 0.4f * a))
            Shapes.rect(x, y, width, height, 0f, Color(K.drawerBg).a(a))
        }
    }

    init {
        setFillParent(true)
        visible = false
        addChild(scrim)
        addChild(panel)
        panel.touchable = Touchable.enabled
        panel.add(panelContent).grow()
    }

    val isOpen get() = open.target() == 1f
    private var fresh = false

    fun open() {
        if (!visible) fresh = true
        visible = true
        open.to(1f)
        scrimA.to(1f)
    }

    /** 宽屏：把面板内容借给常驻侧栏（抽屉收起、不再可点）。 */
    fun lend(): Element {
        open.snap(0f); scrimA.snap(0f); visible = false
        return panelContent
    }

    /** 回到窄屏：把面板内容收回抽屉。 */
    fun reclaim() {
        panel.clearChildren()
        panel.add(panelContent).grow()
    }

    /** 预热：按真实尺寸给面板排一次版（侧边栏一直不可见时不会排版，第一次滑出才排）。 */
    fun prewarm() {
        if (visible || width <= 0f) return
        panel.setSize(dp(320f).coerceAtMost(width * 0.88f), height)
        panel.validate()
    }

    fun close() {
        open.to(0f)
        scrimA.to(0f)
    }

    override fun act(delta: Float) {
        super.act(delta)
        if (!visible) return
        val d = if (fresh) 0f else delta
        fresh = false
        open.update(d)
        scrimA.update(d)
        if (open.get() <= 0.001f && open.target() == 0f && scrimA.get() <= 0.001f) visible = false
        invalidate()
    }

    override fun layout() {
        scrim.setBounds(0f, 0f, width, height)
        val w = dp(320f).coerceAtMost(width * 0.88f)
        panel.setBounds(-(1f - open.get()) * w * 1.05f, 0f, w, height)
        panel.validate()
    }

    override fun hit(x: Float, y: Float, touchable: Boolean): Element? =
        if (!visible || open.target() == 0f) null else super.hit(x, y, touchable)
}

/**
 * 顶部通知横幅（像 iOS 通知）：毛玻璃底、从上方带回弹落下，约 2.6 秒后收回，点一下立即收起。
 */
class Banner : WidgetGroup() {
    // 原型：transform 0.5s cubic-bezier(.18,1.25,.4,1)，opacity 0.25s ease；收起时 translateY(-100% - 24px)
    private val shown = Smooth(0f, 0.5f, Interp.overshoot)
    private val alpha = Smooth(0f, 0.25f, Interp.ease)
    private var hideAt = 0f
    private var clock = 0f
    private val message = K.text("", 14f, K.text, lh = 18.9f).apply { setWrap(true) }
    private val card = object : Table() {
        override fun drawBackground(x: Float, y: Float) {
            val a = color.a * parentAlpha
            if (a <= 0.01f) return
            Shapes.shadow(x, y, width, height, dp(20f), 0f, dp(10f), dp(30f), 0f, Color(0f, 0f, 0f, (if (RwTheme.dark) 0.45f else 0.16f) * a))
            Draw.flush()
            // 跟随深浅色：浅色下是白底毛玻璃，文字用 K.text 才看得清
            Backdrop.draw(x, y, width, height, dp(20f), dp(20f), if (RwTheme.dark) Color(46f / 255f, 46f / 255f, 44f / 255f, 0.92f * a) else Color(1f, 1f, 1f, 0.94f * a))
        }
    }

    init {
        setFillParent(true)
        touchable = Touchable.childrenOnly
        addChild(card)
        card.touchable = Touchable.enabled
        // 点一下：有动作就执行（「助手打开了 xx，点这里查看」），然后收起
        card.clicked { val a = onTap; onTap = null; hide(); a?.invoke() }
        val logo = Table(K.fill(K.accent, 9f))
        logo.add(AnvilLogo(24f, Color.white))
        card.add(logo).size(dp(38f)).padRight(dp(12f))
        val col = Table().left()
        val head = Table()
        head.add(K.text(io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.name, 14f, K.text, 600, 16.8f)).growX().left()
        col.add(head).growX().row()
        col.add(message).growX().left().padTop(dp(4f))
        card.add(col).growX()
        card.margin(dp(12f), dp(14f), dp(12f), dp(14f))
        visible = false
    }

    private var onTap: (() -> Unit)? = null

    /** [tap] 非空时点横幅执行它（不点就只是提示，不会自己跳页面）。 */
    fun show(text: String, seconds: Float = 2.6f, tap: (() -> Unit)? = null) {
        onTap = tap
        message.setText(text)
        visible = true
        shown.to(1f)
        alpha.to(1f)
        hideAt = clock + seconds
    }

    fun hide() {
        shown.to(0f)
        alpha.to(0f)
    }

    override fun act(delta: Float) {
        super.act(delta)
        clock += delta
        if (!visible) return
        if (shown.target() == 1f && clock >= hideAt) hide()
        shown.update(delta)
        alpha.update(delta)
        if (shown.get() <= 0.001f && shown.target() == 0f && alpha.get() <= 0.001f) visible = false
        invalidate()
    }

    override fun layout() {
        // 大屏上横幅最宽 480dp，居中
        val w = minOf(width - dp(16f), dp(480f))
        card.width = w
        card.validate()
        val h = card.prefHeight
        val p = shown.get()
        val top = height - dp(8f)
        card.setBounds((width - w) / 2f, top - h + (1f - p) * (h + dp(24f)), w, h)
        card.color.a = alpha.get()
    }
}

/** 宽度达到这么多 dp 时弹层改为居中限宽（平板竖屏起）。 */
const val WIDE_SHEET_FROM = 700f
