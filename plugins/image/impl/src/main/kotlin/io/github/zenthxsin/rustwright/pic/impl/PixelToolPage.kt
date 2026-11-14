package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.ui.kit.PageBar
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.pageBar
import io.github.zenthxsin.rustwright.ui.kit.OwnHeader
import arc.Core
import arc.graphics.Color
import arc.graphics.Pixmap
import arc.graphics.Texture
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Shapes
import arc.graphics.g2d.TextureRegion
import arc.scene.Element
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.pic.imaging.BackgroundRemovalMode
import io.github.zenthxsin.rustwright.pic.imaging.PaletteMode
import io.github.zenthxsin.rustwright.pic.imaging.PixelArtProcessor
import io.github.zenthxsin.rustwright.pic.imaging.PixelProcessingParams
import io.github.zenthxsin.rustwright.pic.imaging.PixelSamplingMode
import io.github.zenthxsin.rustwright.pic.imaging.PngEncoder
import io.github.zenthxsin.rustwright.pic.imaging.RgbaImage
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import io.github.zenthxsin.rustwright.ui.kit.SheetPage
import io.github.zenthxsin.rustwright.ui.kit.Slider
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 像素化（原型「工具：像素化」）：300×300 预览、像素块大小滑条（6–30，网格 = 300 / 块大小）、
 * 颜色数（8 / 16 / 64 / 不限）、「存到产物」「交给助手改」。源图默认是项目里最新的一张，点预览换图。
 */
class PixelToolPage(private val project: () -> ProjectContext?, private val scope: CoroutineScope) : Table(), OwnHeader {
    private var source: File? = null
    private var decoded: RgbaImage? = null
    private var pixel = 12f
    private var palette = 16
    private var result: RgbaImage? = null
    private var job: Job? = null
    private val preview = Preview()
    private val sizeValue = K.text("", 15f, K.muted, lh = 15f)
    private val chips = Table()

    private val bar = PageBar("像素化") { source?.let { f -> f.name + (decoded?.let { " · ${it.width}×${it.height}" } ?: "") } ?: "点下面的图片选一张" }
    override fun bindBack(back: () -> Unit) = bar.bindBack(back)

    init {
        top()
        pageBar(bar)
        bar.action(PI.img, "选图") { pickSource() }
        bar.action(PI.save, "存到产物") { save() }
        bar.action(PI.chat, "交给${io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.assistant}改") { askRust() }
        // 原型：padding 4 20 24，块间距 18
        val col = Table().top()
        col.margin(dp(4f), dp(20f), dp(24f), dp(20f))
        val pv = Tap(null, 12f) { pickSource() }
        pv.name = "换图"
        pv.add(preview).size(dp(300f))
        col.add(pv).size(dp(300f)).center().row()

        val sizeHead = Table()
        sizeHead.add(K.text("像素块大小", 15f, K.text, lh = 15f)).left().growX()
        sizeHead.add(sizeValue)
        col.add(sizeHead).growX().padTop(dp(18f)).row()
        col.add(Slider(6f, 30f, pixel) { pixel = it; updateLabels(); reprocess() }).growX().height(dp(24f)).padTop(dp(10f)).row()

        col.add(K.text("颜色数", 15f, K.text, lh = 15f)).left().padTop(dp(18f)).row()
        col.add(chips).growX().padTop(dp(10f)).row()


        add(ScrollPane(col).apply { setScrollingDisabledX(true); setOverscroll(false, true) }).grow()
        buildChips()
        updateLabels()
        loadSource(io.github.zenthxsin.rustwright.ui.kit.ToolRequests.take("pixel") ?: project()?.images()?.firstOrNull())
    }

    private fun grid() = Math.max(4, Math.round(300f / pixel))

    private fun updateLabels() {
        val n = grid()
        sizeValue.setText("${pixel.toInt()} px · $n×$n")
    }

    private fun buildChips() {
        chips.clearChildren()
        val opts = listOf(8, 16, 64, 512)
        opts.forEachIndexed { i, v ->
            chips.add(chip(if (v == 512) "不限" else "$v 色", v == palette) { palette = v; buildChips(); reprocess() })
                .growX().uniformX().padLeft(if (i > 0) dp(4f) else 0f).padRight(if (i < opts.lastIndex) dp(4f) else 0f)
        }
    }

    private fun loadSource(f: File?) {
        source = f
        decoded = null
        result = null
        preview.set(null)
        if (f == null) return
        scope.launch(Dispatchers.IO) {
            val img = runCatching { ArcImageDecoder.decode(f.readBytes()) }.getOrNull()
            withContext(Dispatchers.Default) { decoded = img }
            reprocess(0)
        }
    }

    private fun reprocess(debounceMs: Long = 120) {
        val src = decoded ?: return
        job?.cancel()
        val n = grid()
        val colors = palette
        job = scope.launch(Dispatchers.Default) {
            delay(debounceMs)
            val params = PixelProcessingParams(
                outputWidth = n, outputHeight = n,
                samplingMode = PixelSamplingMode.MODERN_V2,
                backgroundRemovalMode = BackgroundRemovalMode.NONE,
                paletteMode = PaletteMode.MEDIAN_CUT,
                maxPaletteColors = colors.coerceAtMost(256),
            )
            val out = runCatching { PixelArtProcessor.process(src, params) }.getOrNull() ?: return@launch
            Core.app.post { result = out; preview.set(out) }
        }
    }

    private fun pickSource() {
        val ctx = project() ?: return
        val imgs = ctx.images()
        if (imgs.isEmpty()) return ctx.notify("项目里还没有图片，先让${io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.assistant}画一张，或用「生图」")
        val g = ListGroup()
        for (f in imgs.take(40)) {
            val row = Tap(K.surface, 0f) { ctx.closeSheet(); loadSource(f) }
            row.name = f.name
            row.margin(dp(10f), dp(16f), dp(10f), dp(16f))
            row.add(Table(K.fill(K.input, 8f)).apply { clipRadius = dp(8f); add(io.github.zenthxsin.rustwright.ui.kit.ImageThumb(f)).grow() }).size(dp(44f)).padRight(dp(14f))
            row.add(K.text(f.name, 16f, K.text, lh = 16f).apply { setEllipsis(true) }).growX().minWidth(0f).left()
            g.row(row)
        }
        ctx.sheet(SheetPage("选择图片", Table().top().apply { add(g).growX() }, height = 540f))
    }

    private fun save() {
        val ctx = project() ?: return
        val img = result ?: return ctx.notify("还没有可保存的像素图")
        val dir = ctx.projectDir() ?: return ctx.notify("先选一个项目")
        val name = (source?.nameWithoutExtension ?: "pixel") + "_px${grid()}.png"
        scope.launch(Dispatchers.IO) {
            val f = File(dir, "images/$name").apply { parentFile.mkdirs() }
            f.writeBytes(PngEncoder.encode(img))
            Core.app.post { ctx.notify("已存到产物：images/$name") }
        }
    }

    private fun askRust() {
        val ctx = project() ?: return
        val src = source?.let { f -> ctx.projectDir()?.let { d -> f.relativeToOrNull(d)?.path } ?: f.name }
        ctx.askRust(if (src != null) "把 $src 像素化成 ${grid()}×${grid()}、$palette 色以内，做成单位贴图" else "帮我把一张图片像素化成游戏贴图")
    }

    /** 像素图预览：最近邻放大铺满 300×300，圆角 12；没有图时显示提示。 */
    private class Preview : Element() {
        private var region: TextureRegion? = null
        private val hint = K.text("点这里选一张图", 15f, K.subtle)

        fun set(img: RgbaImage?) {
            region?.texture?.dispose()
            region = img?.let {
                val pm = Pixmap(it.width, it.height)
                for (y in 0 until it.height) for (x in 0 until it.width) {
                    val argb = it.pixels[y * it.width + x]
                    pm.set(x, y, (argb shl 8) or (argb ushr 24))
                }
                val t = Texture(pm)
                t.setFilter(Texture.TextureFilter.nearest)
                pm.dispose()
                TextureRegion(t)
            }
        }

        override fun draw() {
            val a = parentAlpha * color.a
            val r = region
            Shapes.clipBegin(x, y, width, height, dp(12f))
            Shapes.rect(x, y, width, height, 0f, Color(K.input).a(a))
            if (r != null) {
                Draw.color(1f, 1f, 1f, a)
                Draw.rect(r, x + width / 2f, y + height / 2f, width, height)
                Draw.reset()
            }
            Shapes.clipEnd()
            if (r == null) {
                hint.setPosition(x + (width - hint.prefWidth) / 2f, y + (height - hint.prefHeight) / 2f)
                hint.setSize(hint.prefWidth, hint.prefHeight)
                hint.validate()
                hint.draw()
            }
        }
    }
}
