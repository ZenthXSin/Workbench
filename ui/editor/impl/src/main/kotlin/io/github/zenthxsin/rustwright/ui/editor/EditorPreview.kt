package io.github.zenthxsin.rustwright.ui.editor

import arc.Core
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.editor.api.EditorHost
import io.github.zenthxsin.rustwright.ui.kit.*
import kotlinx.coroutines.*
import java.io.File

/** File preview resources and pending image loads share one lifecycle. */
internal class EditorPreview(
    private val scope: CoroutineScope,
    private val target: Table,
    private val host: () -> EditorHost,
    private val attached: () -> Boolean,
) {
    private var generation = 0
    private var job: Job? = null

    fun clear() {
        generation++
        job?.cancel()
        texture?.dispose()
        texture = null
    }

    private var texture: arc.graphics.Texture? = null

    /** 非文本文件的预览：图片显示图（等比缩放）和尺寸，其他显示文件信息；都能「用其他方式打开」。 */
    fun show(f: File) {
        clear()
        val box = Table()
        val isImage = f.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        val imgCell = box.add().size(dp(200f))
        box.row()
        box.add(K.text(f.name, 14f, K.text, 500)).padTop(dp(12f)).row()
        val info = K.text(sizeText(f.length()), 12f, K.subtle)
        box.add(info).padTop(dp(4f)).row()
        val other = Tap(K.surface2, 10f) { host().project()?.openTool("file", f) }
        other.name = "用其他方式打开"
        other.margin(0f, dp(14f), 0f, dp(14f))
        other.add(K.text("用其他方式打开", 13f, K.text))
        box.add(other).height(dp(36f)).padTop(dp(14f))
        target.add(box)
        if (!isImage) { imgCell.setElement(K.svg(PI.file, 48f, K.subtle, 1.6f)).size(dp(64f)); return }
        val want = f
        val previewSerial = generation
        job = scope.launch(Dispatchers.IO) {
            val bytes = runCatching { want.readBytes() }.getOrNull()
            Core.app.post {
                if (host().file != want || generation != previewSerial || bytes == null || !attached()) return@post
                val pm = runCatching { arc.graphics.Pixmap(bytes) }.getOrNull() ?: return@post
                val tex = arc.graphics.Texture(pm).apply { setFilter(arc.graphics.Texture.TextureFilter.nearest) }
                texture = tex
                info.setText("${pm.width} × ${pm.height} · " + sizeText(f.length()))
                pm.dispose()
                // 像素图放大看得清：整数倍放大、最近邻，不超过 260dp
                val maxSide = dp(260f)
                val k = maxOf(1, minOf((maxSide / maxOf(tex.width, tex.height)).toInt(), 8))
                val w = minOf(tex.width * k.toFloat(), maxSide); val h = minOf(tex.height * k.toFloat(), maxSide)
                imgCell.setElement(arc.scene.ui.Image(arc.graphics.g2d.TextureRegion(tex)).apply { setScaling(arc.util.Scaling.fit) }).size(w, h)
                box.invalidateHierarchy()
            }
        }
    }

    private fun sizeText(n: Long) = when { n < 1024 -> "$n B"; n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0); else -> "%.1f MB".format(n / 1048576.0) }

    fun markdown() {
        val ctx = host().project() ?: return
        ctx.sheet(SheetPage("预览 · ${host().file?.name}", ScrollPane(Table().top().left().apply {
            margin(dp(12f)); add(io.github.zenthxsin.rustwright.ui.widgets.MarkdownView().apply { setMarkdown(host().editor.doc.text) }).growX()
        }).apply { setScrollingDisabledX(true) }, height = 720f))
    }

}
