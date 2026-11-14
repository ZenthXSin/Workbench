package io.github.zenthxsin.rustwright

import arc.Core
import io.github.zenthxsin.rustwright.core.runtime.RwContext
import io.github.zenthxsin.rustwright.core.runtime.RwModule
import io.github.zenthxsin.rustwright.pic.api.EditPixelImage
import io.github.zenthxsin.rustwright.pic.impl.PixelEditorPanel
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext

/** Image-specific editor events do not pass through the application launcher. */
object ImagePlugin : RwModule {
    override val id = "image.integration"
    override val dependsOn = listOf("pic")
    override fun init(ctx: RwContext) {
        ctx.extensions.add(ShellPoints.NAVIGATION, NavigationAlias("pic", "imagegen"))
        fun openPixel(file: java.io.File) { Core.app.post {
            val ui = ctx.services.getOrNull<ProjectContext>() ?: return@post
            runCatching { PixelEditorPanel(file, ctx.scope, onBack = { ui.back() }) {} }
                .fold({ ui.openPage("像素编辑", it) }, { ui.notify("无法打开图片：${it.message}") })
        } }
        ctx.extensions.add(ShellPoints.COMMANDS, ToolCommand("pixel-editor", { file ->
            if (file == null) false else { openPixel(file); true }
        }))
        ctx.events.on<EditPixelImage>(ctx.scope) { event -> openPixel(event.file) }
    }
}
