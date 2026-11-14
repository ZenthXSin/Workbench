package io.github.zenthxsin.rustwright.ui.designsystem

import arc.scene.Element

/** 一个顶层页面入口（底部导航的一项），由模块在 init 时注册。 */
/**
 * 一个底部导航页面。外壳只调用一次 [build] 并在切页时复用同一个元素；切走时会经 Table.clearChildren 调用元素的 remove()，
 * 所以页面不要在 remove() 里取消监听或释放资源，否则切回来就不再更新。
 */
class Screen(
    val id: String,
    val title: String,
    /** 越小越靠前。 */
    val order: Int = 100,
    /** 底栏图标（[Icons]）。 */
    val icon: Char = Icons.home,
    val build: () -> Element,
)

/** 页面注册表：模块只注册，不关心外壳怎么摆放。 */
class ScreenRegistry {
    private val screens = LinkedHashMap<String, Screen>()

    fun register(screen: Screen) {
        check(screen.id !in screens) { "页面 id 重复：${screen.id}" }
        screens[screen.id] = screen
    }

    val all: List<Screen> get() = screens.values.sortedBy { it.order }
}

/** 事件：外壳切到了某个页面（助手据此切换场景）。 */
data class ScreenShown(val id: String)

/** 事件：请求外壳切到某个页面（模块之间跳转用，如从模组页打开像素编辑器）。 */
data class NavigateTo(val id: String)

/** 事件：请求切换主题模式（外壳保存设置、刷新颜色并重建界面）。 */
data class SetThemeMode(val mode: RwTheme.Mode)
