package io.github.zenthxsin.rustwright.ui.designsystem

import arc.graphics.Color
import arc.scene.Element
import arc.scene.style.Drawable
import arc.scene.ui.Button
import arc.scene.ui.Label
import arc.scene.ui.TextButton
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.util.Align

/** dp → 像素。 */
fun dp(v: Float): Float = Scl.scl(v)

/** 文字按钮，可带前置图标；图标颜色跟随文字状态色。 */
open class RwButton(
    text: String,
    icon: Char? = null,
    style: TextButtonStyle = RwTheme.Styles.secondary,
    /** 图标放在文字后面（下拉箭头）。 */
    trailing: Boolean = false,
) : TextButton(text, style) {
    val iconLabel: Label? = icon?.let { Label(it.toString(), Label.LabelStyle(if (style.font === RwTheme.small) RwTheme.iconsSmall else RwTheme.icons, style.fontColor)) }

    init {
        margin(0f, dp(10f), 0f, dp(10f))
        label.setWrap(false)
        if (iconLabel != null) {
            clearChildren()
            if (trailing) {
                add(label)
                add(iconLabel).padLeft(dp(3f))
            } else {
                add(iconLabel).padRight(dp(5f))
                add(label)
            }
        }
    }

    override fun act(delta: Float) {
        super.act(delta)
        iconLabel?.style?.fontColor = label.style.fontColor
    }

    override fun getPrefHeight(): Float = maxOf(super.getPrefHeight(), dp(if (style.font === RwTheme.small) 28f else RwTheme.Dimens.control))

    override fun setText(text: String?) {
        if (text == this.text.toString()) return
        super.setText(text)
        invalidateHierarchy()
    }
}

/** 圆形图标按钮；[desc] 作为元素名（验收脚本按名字点击）。 */
class IconButton(icon: Char, val desc: String, style: TextButtonStyle = RwTheme.Styles.icon) : TextButton(icon.toString(), style) {
    init {
        name = desc
    }

    override fun getPrefWidth(): Float = dp(34f)
    override fun getPrefHeight(): Float = dp(34f)
}

/** 组件工厂：页面统一用这些搭界面，保证间距、字号、圆角一致。 */
object Rw {
    fun primary(text: String, icon: Char? = null, onClick: () -> Unit = {}) =
        RwButton(text, icon, RwTheme.Styles.primary).apply { clicked(onClick) }

    fun secondary(text: String, icon: Char? = null, onClick: () -> Unit = {}) =
        RwButton(text, icon, RwTheme.Styles.secondary).apply { clicked(onClick) }

    fun ghost(text: String, icon: Char? = null, onClick: () -> Unit = {}) =
        RwButton(text, icon, RwTheme.Styles.ghost).apply { clicked(onClick) }

    fun danger(text: String, icon: Char? = null, onClick: () -> Unit = {}) =
        RwButton(text, icon, RwTheme.Styles.danger).apply { clicked(onClick) }

    /** 胶囊：参数 / 筛选，文字用 [RwTheme.small]。 */
    fun chip(text: String, icon: Char? = null, onClick: () -> Unit = {}) =
        RwButton(text, icon, RwTheme.Styles.chip).apply {
            margin(0f, dp(9f), 0f, dp(9f))
            clicked(onClick)
        }

    fun icon(icon: Char, desc: String, onClick: () -> Unit = {}) = IconButton(icon, desc).apply { clicked(onClick) }

    fun iconPrimary(icon: Char, desc: String, onClick: () -> Unit = {}) = IconButton(icon, desc, RwTheme.Styles.iconPrimary).apply { clicked(onClick) }

    fun title(text: String) = Label(text, RwTheme.Styles.title).apply { setEllipsis(true) }

    fun caption(text: String) = Label(text, RwTheme.Styles.caption)

    fun section(text: String) = Label(text, RwTheme.Styles.section)

    fun card(background: Drawable = RwTheme.card()) = Table(background).apply {
        margin(dp(10f))
    }

    /**
     * 页面顶栏：可选返回按钮、标题（可带副标题）、右侧操作。高 56dp。
     * [titleElement] 用于标题可点击的情况（如模组选择）。
     */
    fun topBar(
        title: String,
        subtitle: String? = null,
        onBack: (() -> Unit)? = null,
        titleElement: Element? = null,
        actions: List<Element> = emptyList(),
    ): Table = Table().apply {
        left()
        margin(0f, dp(if (onBack != null) 4f else RwTheme.Dimens.gutter), 0f, dp(5f))
        if (onBack != null) add(icon(Icons.back, "返回", onBack)).padRight(dp(3f))
        val head = titleElement ?: Table().left().apply {
            add(title(title)).left().growX().minWidth(0f).row()
            if (subtitle != null) add(caption(subtitle).apply { setEllipsis(true) }).left().growX().minWidth(0f)
        }
        add(head).growX().minWidth(0f).left()
        actions.forEach { add(it).padLeft(dp(2f)) }
    }.also { it.cells.firstOrNull()?.minHeight(dp(RwTheme.Dimens.topBar)) }

    /** 列表行：图标、标题、副标题、右侧附件，整行可点。 */
    fun listItem(
        title: String,
        subtitle: String? = null,
        icon: Char? = null,
        iconColor: Color = RwTheme.Colors.muted,
        trailing: Element? = null,
        /** 图标放在同色浅底圆里（首页入口等）。 */
        iconBadge: Boolean = false,
        /** 指定图标底色（隐含 [iconBadge]）。 */
        iconBg: Color? = null,
        /** 每帧刷新的副标题（优先于 [subtitle]）。 */
        liveSubtitle: (() -> String)? = null,
        onClick: (() -> Unit)? = null,
    ): Button = Button(Button.ButtonStyle().apply {
        // 平铺行：放进 [group] 的卡片里，按下时浅灰
        down = RwTheme.solid(RwTheme.Colors.surface2)
    }).apply {
        name = title
        left()
        margin(dp(9f), dp(10f), dp(9f), dp(9f))
        if (icon != null) {
            val glyph = Label(icon.toString(), Label.LabelStyle(RwTheme.icons, iconColor))
            if (iconBadge || iconBg != null) {
                add(Table(RwTheme.round(RwTheme.Dimens.radius, iconBg ?: iconColor.cpy().a(0.16f))).apply { add(glyph) }).size(dp(30f)).padRight(dp(10f))
            }
            else add(glyph).padRight(dp(10f))
        }
        add(Table().left().apply {
            add(Label(title).apply { setEllipsis(true) }).left().growX().minWidth(0f).row()
            if (subtitle != null || liveSubtitle != null) {
                add(caption(subtitle.orEmpty()).apply {
                    setWrap(true)
                    if (liveSubtitle != null) update { setText(runCatching(liveSubtitle).getOrDefault("")) }
                }).left().growX().minWidth(0f).padTop(dp(2f))
            }
        }).growX().minWidth(0f)
        when {
            trailing != null -> add(trailing).padLeft(dp(6f))
            onClick != null -> add(Label(Icons.chevronRight.toString(), Label.LabelStyle(RwTheme.iconsSmall, RwTheme.Colors.muted))).padLeft(dp(6f))
        }
        if (onClick != null) clicked(onClick) else touchable = arc.scene.event.Touchable.childrenOnly
    }

    /** 分组卡片：几行 [listItem] 放在一张卡片里，行间用缩进分隔线。 */
    fun group(vararg rows: Element) = Table(RwTheme.card(radiusDp = 10)).apply {
        rows.forEachIndexed { i, row ->
            if (i > 0) add(divider()).growX().height(dp(1f)).padLeft(dp(12f)).row()
            add(row).growX().row()
        }
    }

    /** 空状态：大图标 + 说明 + 可选操作。 */
    fun empty(icon: Char, text: String, action: Element? = null) = Table().apply {
        add(Label(icon.toString(), Label.LabelStyle(RwTheme.icons, RwTheme.Colors.outline)).apply { setFontScale(2f) }).row()
        add(caption(text).apply { setAlignment(Align.center); setWrap(true) }).width(dp(260f)).padTop(dp(8f)).row()
        if (action != null) add(action).padTop(dp(12f))
    }

    /** 分段控件：浅色轨道里几段按钮，[selected] 每帧读取当前项。 */
    fun segmented(options: List<String>, selected: () -> Int, onSelect: (Int) -> Unit) =
        Table(RwTheme.round(10, RwTheme.Colors.surface2)).apply {
            margin(dp(3f))
            options.forEachIndexed { i, text ->
                add(TextButton(text, RwTheme.Styles.segment).apply {
                    margin(0f, dp(9f), 0f, dp(9f))
                    update { isChecked = selected() == i }
                    clicked { onSelect(i) }
                }).height(dp(26f)).padLeft(if (i == 0) 0f else dp(2f))
            }
        }

    /** 带浅底的小图标块（头像、状态）。 */
    fun badge(icon: Char, fg: Color, bg: Color, size: Float = 28f, small: Boolean = true) =
        Table(RwTheme.round(8, bg)).apply {
            add(Label(icon.toString(), Label.LabelStyle(if (small) RwTheme.iconsSmall else RwTheme.icons, fg)))
            setSize(dp(size), dp(size))
        }

    /** 1dp 分隔线。 */
    fun divider() = arc.scene.ui.Image(RwTheme.solid(RwTheme.Colors.outline))
}
