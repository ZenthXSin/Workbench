package io.github.zenthxsin.rustwright.ui.kit

import arc.graphics.Color
import arc.graphics.g2d.Shapes
import arc.math.Interp
import arc.math.Smooth
import arc.scene.Element
import arc.scene.event.ClickListener
import arc.scene.event.Touchable
import arc.scene.ui.Label
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.dp

/**
 * 可点的块：圆角底色 + 按下时的浅色遮罩（等价网页 :active），底色变化平滑过渡。
 * 半径、颜色都是 dp / 令牌；[fill] 为 null 时只有按下效果。
 */
open class Tap(fill: Color?, var radius: Float, onClick: (() -> Unit)? = null) : Table() {
    private val target = Color(fill ?: Color.clear)
    private val current = Color(fill ?: Color.clear)
    private val press = Smooth(0f, 0.12f, Interp.standard)
    var border: Color? = null
    var borderWidth = 1f
    var enabled = true
    /** 按下时叠一层浅色：手机上没有悬停，按下得有反馈（审查后默认打开）。 */
    var pressOverlay = true
    /** 最小高度（dp 换算后的像素），用于列表行等。 */
    var minH = 0f
    /** 最小宽度（像素）。 */
    var minW = 0f
    private var listener: ClickListener? = null
    /** 长按（按住 0.45 秒不动）：触发后这次就不算点击。 */
    var onLongPress: (() -> Unit)? = null
    private var pressedFor = 0f
    private var longFired = false

    override fun getPrefHeight() = maxOf(super.getPrefHeight(), minH)
    override fun getMinHeight() = maxOf(super.getMinHeight(), minH)
    override fun getPrefWidth() = maxOf(super.getPrefWidth(), minW)
    override fun getMinWidth() = maxOf(super.getMinWidth(), minW)

    init {
        touchable = Touchable.enabled
        if (onClick != null) onTap(onClick)
    }

    fun onTap(action: () -> Unit): Tap {
        listener?.let { removeListener(it) }
        listener = clicked { if (enabled && !longFired) action() }
        return this
    }

    /** 改底色（平滑过渡）。 */
    fun fill(color: Color?): Tap {
        target.set(color ?: Color.clear)
        return this
    }

    override fun act(delta: Float) {
        super.act(delta)
        press.to(if (enabled && listener?.isPressed == true) 1f else 0f).update(delta)
        val l = listener
        if (l != null && l.isPressed && onLongPress != null) {
            if (pressedFor == 0f) longFired = false
            pressedFor += delta
            if (!longFired && pressedFor > 0.45f && l.isOver) {
                longFired = true
                l.cancel()
                onLongPress?.invoke()
            }
        } else if (l?.isPressed != true) pressedFor = 0f
        // 原型里底色切换没有过渡
        current.set(target)
    }

    override fun drawBackground(x: Float, y: Float) {
        val a = color.a * parentAlpha
        val r = dp(radius)
        if (current.a > 0f || border != null) {
            val f = Color(current).a(current.a * a)
            val b = border?.let { Color(it).a(it.a * a) }
            Shapes.rect(x, y, width, height, r, if (f.a > 0f) f else null, if (b != null) dp(borderWidth) else 0f, b)
        }
        // 按下态：深色主题叠白、浅底按钮叠黑，0.12s 渐入、松手渐出
        val p = press.get()
        if (pressOverlay && p > 0.001f) {
            val lightFill = current.a > 0.5f && (current.r + current.g + current.b) / 3f > 0.6f
            Shapes.rect(x, y, width, height, r, if (lightFill) Color(0f, 0f, 0f, 0.10f * p * a) else Color(1f, 1f, 1f, 0.07f * p * a))
        }
    }
}

/** 圆形图标按钮（默认 48dp 触控区）。 */
fun iconButton(content: Element, size: Float = 48f, fill: Color? = null, onClick: () -> Unit): Tap =
    Tap(fill, size / 2f, onClick).apply { add(content) }.also { it.minW = dp(size); it.minH = dp(size) }

fun iconButton(glyph: Char, px: Float = 22f, size: Float = 48f, color: Color = K.text, fill: Color? = null, stroke: Float = 2f, onClick: () -> Unit): Tap =
    iconButton(K.icon(glyph, px, color, stroke), size, fill, onClick)

fun iconButton(path: String, px: Float = 22f, size: Float = 48f, color: Color = K.text, fill: Color? = null, stroke: Float = 2f, onClick: () -> Unit): Tap =
    iconButton(K.svg(path, px, color, stroke), size, fill, onClick)

/** 胶囊 / 圆角按钮：文字（可带前置图标）。 */
fun pill(
    text: String, height: Float = 40f, radius: Float = 20f, fill: Color? = K.surface2, fg: Color = K.text,
    px: Float = 14f, weight: Int = 400, icon: Char? = null, padX: Float = 14f, onClick: () -> Unit,
): Tap = Tap(fill, radius, onClick).apply {
    if (icon != null) add(K.icon(icon, 16f, fg)).padRight(dp(8f))
    add(K.text(text, px, fg, weight))
    margin(0f, dp(padX), 0f, dp(padX))
}.also { it.minH = dp(height); it.name = text }

/**
 * 列表组：圆角 12、行与行之间 2dp 缝（缝透出页面底色），等价原型里设置 / 选择模型的分组卡片。
 */
class ListGroup : Table() {
    init {
        clipRadius = dp(12f)
        defaults().growX()
    }

    fun row(r: Element): ListGroup {
        if (children.size > 0) row()
        add(r).padTop(if (children.size > 1) dp(2f) else 0f)
        return this
    }
}

/**
 * 列表行：可选左侧图标、标题、可选说明、可选右侧文字 / 箭头 / 自定义元素。
 */
fun listRow(
    title: String, sub: String? = null, icon: Any? = null, iconColor: Color = K.text,
    trailing: String? = null, chevron: Boolean = false, titleColor: Color = K.text, subColor: Color = K.subtle,
    minHeight: Float = 64f, extra: Element? = null, iconStroke: Float = 1.5f,
    /** 右侧值会变（权限、余额……）时传入：每秒重新取一次。 */
    liveTrailing: (() -> String?)? = null, onClick: (() -> Unit)? = null,
): Tap = Tap(K.surface, 0f, onClick).apply {
    margin(dp(10f), dp(18f), dp(10f), dp(18f))
    // icon：原型 svg 路径（String，见 PI）或 Lucide 字形（Char）
    when (icon) {
        is String -> add(K.svg(icon, 24f, iconColor, iconStroke)).padRight(dp(18f))
        is Char -> add(K.icon(icon, 24f, iconColor)).padRight(dp(18f))
    }
    val col = Table().left()
    col.add(K.text(title, 17f, titleColor, lh = 17f).apply { setEllipsis(true) }).growX().left().minWidth(0f)
    if (extra != null) col.add(extra).padLeft(dp(8f)).left()
    if (sub != null) {
        col.row()
        col.add(K.text(sub, 14f, subColor, lh = 14f).apply { setEllipsis(true) }).growX().left().minWidth(0f).colspan(2).padTop(dp(6f))
    }
    // 原型文字整体上移 2px
    add(col).growX().minWidth(0f).left().padBottom(dp(4f))
    // 右侧值过长时省略，不把标题挤没；短的状态（「已开启」）先让说明省略，自己保持完整
    if (trailing != null) {
        val label = K.text(trailing, 16f, K.muted).apply { setEllipsis(true); setAlignment(arc.util.Align.right) }
        if (liveTrailing != null) {
            var last = System.currentTimeMillis()
            label.update {
                val now = System.currentTimeMillis()
                if (now - last >= 1000) { last = now; liveTrailing()?.let { if (it != label.text.toString()) label.setText(it) } }
            }
        }
        add(label).minWidth(if (label.prefWidth <= dp(72f)) label.prefWidth else 0f).maxWidth(dp(160f)).padLeft(dp(10f))
    }
    if (chevron) add(K.svg(PI.chevronRight, 18f, K.subtle, 2f)).padLeft(dp(6f))
}.also { it.minH = dp(minHeight); it.name = title }

/** 小标签（如「推荐」「低价」）。 */
fun tag(text: String, fg: Color = K.muted, bg: Color = K.surface3): Table =
    Table(K.fill(bg, 6f)).apply { add(K.text(text, 12f, fg, 600)).pad(dp(2f), dp(7f), dp(2f), dp(7f)) }

/** 分组标题（灰色 15px）。 */
fun sectionTitle(text: String): Label = K.text(text, 15f, K.subtle, 500)



/**
 * 工具页自己画顶栏：把返回、标题和页面自己的操作融成一行（如 INI 编辑器的 文件名 + 撤销 / 重做 / 保存），
 * 外壳的统一顶栏这时收起。[bindBack] 在页面打开时传入外壳的返回动作。
 */
interface OwnHeader {
    fun bindBack(back: () -> Unit)
}

/** 工具页可以接管标题栏右上角按钮（如终端的「虚拟机状态」）。 */
interface HeaderAction {
    /** 无障碍 / 测试用的名称。 */
    val headerLabel: String
    fun onHeaderAction()
}
