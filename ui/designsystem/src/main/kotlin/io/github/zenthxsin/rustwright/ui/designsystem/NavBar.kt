package io.github.zenthxsin.rustwright.ui.designsystem

import arc.scene.ui.Button
import arc.scene.ui.Label
import arc.scene.ui.layout.Table

/** 底部导航：图标 + 小字，选中项图标背后有浅色胶囊。 */
class NavBar(items: List<Screen>, private val selected: () -> String?, onSelect: (String) -> Unit) : Table() {
    init {
        background(RwTheme.solid(RwTheme.Colors.surface))
        add(Rw.divider()).growX().height(dp(1f)).colspan(items.size.coerceAtLeast(1)).row()
        items.forEach { s ->
            add(Item(s).apply { clicked { onSelect(s.id) } }).growX().minWidth(0f).height(dp(54f))
        }
    }

    private inner class Item(private val screen: Screen) : Button(Button.ButtonStyle()) {
        private val pill = Table()
        private val pillBg = RwTheme.round(13, RwTheme.Colors.accentSoft)
        private val icon = Label(screen.icon.toString(), Label.LabelStyle(RwTheme.icons, RwTheme.Colors.muted))
        private val text = Label(screen.title, Label.LabelStyle(RwTheme.small, RwTheme.Colors.muted))

        init {
            name = "nav-" + screen.id
            pill.add(icon)
            add(pill).size(dp(42f), dp(26f)).row()
            add(text).padTop(dp(3f))
        }

        // 等分宽度：首选宽度报 0，空间由 growX 平均分配，窄屏也不会溢出
        override fun getPrefWidth(): Float = 0f
        override fun getMinWidth(): Float = 0f

        override fun act(delta: Float) {
            super.act(delta)
            val on = selected() == screen.id
            val bg = if (on) pillBg else null
            if (pill.background !== bg) pill.background = bg
            val c = if (on) RwTheme.Colors.accentText else RwTheme.Colors.muted
            icon.style.fontColor = c
            text.style.fontColor = if (on) RwTheme.Colors.text else c
        }
    }
}
