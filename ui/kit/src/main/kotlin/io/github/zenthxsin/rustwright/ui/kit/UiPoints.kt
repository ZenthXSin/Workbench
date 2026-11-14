package io.github.zenthxsin.rustwright.ui.kit

import arc.graphics.Color
import arc.scene.Element
import io.github.zenthxsin.rustwright.core.runtime.Contribution
import io.github.zenthxsin.rustwright.core.runtime.ExtensionPoint

/**
 * 界面的贡献点：任何模块在生命周期里往 `ctx.extensions` 注册，外壳只负责排序和渲染。
 * id 带模块前缀（`linux.terminal`）；同 id 后注册的覆盖先注册的。
 *
 * ```
 * ctx.extensions.add(UiPoints.TOOLS, ToolPage("demo.hello", "你好", PI.sparkle, group = UiPoints.GROUP_PROJECT) { HelloPage() })
 * ```
 */
object UiPoints {
    /** 「更多」里的分组。 */
    val MORE_GROUPS = ExtensionPoint<MoreGroup>("ui.more.groups")
    /** 「更多」里的工具页；[ToolPage.group] 为 null 时不出现在网格里，只能用 id 打开。 */
    val TOOLS = ExtensionPoint<ToolPage>("ui.more.tools")
    /** 设置页的分组（小标题）。 */
    val SETTING_SECTIONS = ExtensionPoint<SettingSection>("ui.settings.sections")
    /** 设置页的一行。 */
    val SETTINGS = ExtensionPoint<SettingItem>("ui.settings.items")
    /** 侧边栏里「对话 / 项目 / 产物 / 更多」之后的入口。 */
    val DRAWER = ExtensionPoint<DrawerItem>("ui.drawer")
    /** 对话输入框「+」弹层里的动作。 */
    val ATTACH_ACTIONS = ExtensionPoint<SheetAction>("ui.sheet.attach")

    /** 本体自带的「更多」分组。 */
    const val GROUP_ENV = "core.env"
    const val GROUP_PROJECT = "core.project"

    /** 本体自带的设置分组。 */
    const val SECTION_ACCOUNT = "core.account"
    const val SECTION_AGENT = "core.agent"
    const val SECTION_SYSTEM = "core.system"
}

/** 「更多」分组；颜色按当前主题取（用 [K.dl]），所以是函数。 */
class MoreGroup(
    override val id: String,
    val title: String,
    override val order: Int = 100,
    val tint: () -> Color = { K.surface3 },
    val ink: () -> Color = { K.text },
) : Contribution

/**
 * 一个工具页。[build] 为 null 时显示占位页（说明 +「让助手代劳」，[ask] 是交给助手的话）；
 * [fresh] 时每次打开都新建（跟着当前项目走的页面），否则建一次后复用。
 */
class ToolPage(
    override val id: String,
    val name: String,
    val icon: String,
    val group: String?,
    override val order: Int = 100,
    val desc: () -> String = { "" },
    val ask: String = "",
    val fresh: Boolean = false,
    val build: (() -> Element)? = null,
) : Contribution

class SettingSection(
    override val id: String,
    val title: String,
    override val order: Int = 100,
) : Contribution

/** 设置页一行：[value] 是右侧的当前值（过长省略），[open] 点开时调用。 */
class SettingItem(
    override val id: String,
    val section: String,
    val title: String,
    val icon: String,
    override val order: Int = 100,
    val value: () -> String? = { null },
    val open: (ProjectContext) -> Unit,
) : Contribution

class DrawerItem(
    override val id: String,
    val label: String,
    val icon: String,
    override val order: Int = 100,
    val open: (ProjectContext) -> Unit,
) : Contribution

/** 弹层里的一项动作。 */
class SheetAction(
    override val id: String,
    val label: String,
    val icon: String,
    val desc: String = "",
    override val order: Int = 100,
    val run: (ProjectContext) -> Unit,
) : Contribution
