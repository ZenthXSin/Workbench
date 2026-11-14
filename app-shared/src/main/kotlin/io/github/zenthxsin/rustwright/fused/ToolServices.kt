package io.github.zenthxsin.rustwright.fused

import java.io.File

/** ToolServices: a separately implementable workbench capability. */
interface ToolServices {
    /** 「更多」里的工具分组（来自 [io.github.zenthxsin.rustwright.ui.kit.UiPoints.TOOLS]）。 */
    fun tools(): List<ToolGroup>
    /** 按 id 找工具页，包括不在「更多」网格里的。 */
    fun tool(id: String): Pair<ToolEntry, ToolGroup>? =
        tools().firstNotNullOfOrNull { g -> g.tools.firstOrNull { it.id == id }?.let { it to g } }
    /** 侧边栏里扩展的入口。 */
    fun drawerItems(): List<io.github.zenthxsin.rustwright.ui.kit.DrawerItem> = emptyList()
    /** 「添加上下文」弹层里扩展的动作。 */
    fun attachActions(): List<io.github.zenthxsin.rustwright.ui.kit.SheetAction> = emptyList()
    /** 多模态输入的来源：camera 拍照 / photo 相册 / file 任意文件；取消或不支持返回 null。 */
    suspend fun pickAttachment(kind: String): java.io.File? = null
    suspend fun pickAttachments(kind: String): List<java.io.File> = listOfNotNull(pickAttachment(kind))
    /** Rustwright 自带的工具组（连接器里的「内置」，每组是一个 MCP 服务器）。 */
    fun toolsets(): List<BuiltinToolset> = emptyList()
    /** 开关一组内置工具；会重启助手。 */
    fun setToolsetEnabled(id: String, enabled: Boolean) {}
}
