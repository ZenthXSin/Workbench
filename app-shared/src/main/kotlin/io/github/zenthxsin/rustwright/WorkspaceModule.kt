package io.github.zenthxsin.rustwright

import io.github.zenthxsin.rustwright.core.runtime.AppIdentity
import io.github.zenthxsin.rustwright.core.runtime.RwContext
import io.github.zenthxsin.rustwright.core.runtime.RwModule
import io.github.zenthxsin.rustwright.core.workspace.WorkspaceService
import io.github.zenthxsin.rustwright.ui.designsystem.Icons
import io.github.zenthxsin.rustwright.ui.designsystem.Screen
import io.github.zenthxsin.rustwright.ui.designsystem.ScreenRegistry
import io.github.zenthxsin.rustwright.ui.kit.MoreGroup
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import io.github.zenthxsin.rustwright.ui.kit.ToolPage
import io.github.zenthxsin.rustwright.ui.kit.UiPoints
import io.github.zenthxsin.rustwright.ui.editor.FilesToolPage

object WorkspaceModule : RwModule {
    override val id = "workspace"
    override fun register(ctx: RwContext) {
        ctx.services.provideLazy { WorkspaceService(ctx.extensions, ctx.platform.dataDir) {
            ctx.services.getOrNull<AppIdentity>()?.projectType ?: "directory"
        } }
    }
    override fun init(ctx: RwContext) {
        val editor = { io.github.zenthxsin.rustwright.ui.editor.EditorPage({ ctx.services.getOrNull<ProjectContext>() }, ctx.scope, supportFactory = { file -> ctx.extensions.all(io.github.zenthxsin.rustwright.ui.editor.api.EditorPoints.SUPPORTS).firstOrNull { it.accepts(file) }?.create?.invoke() ?: io.github.zenthxsin.rustwright.ui.editor.BuiltinEditorSupport() }) }
        ctx.extensions.add(UiPoints.TOOLS, ToolPage("editor", "编辑器", PI.code, UiPoints.GROUP_PROJECT, order = 11,
            desc = { "通用代码、配置和 Markdown 编辑器" }, fresh = true, build = editor))
        val files = { FilesToolPage({ ctx.services.getOrNull<ProjectContext>() }, ctx.scope) }
        ctx.service<ScreenRegistry>().register(Screen("files", "文件管理", order = 14, icon = Icons.extension, build = files))
        ctx.extensions.add(UiPoints.MORE_GROUPS, MoreGroup(UiPoints.GROUP_PROJECT, "项目", order = 10))
        ctx.extensions.add(UiPoints.TOOLS, ToolPage("files", "文件管理", PI.folder, UiPoints.GROUP_PROJECT, order = 10,
            desc = { "浏览、导入、整理项目文件" }, fresh = true, build = files))
    }
}
