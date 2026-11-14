package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.ui.kit.PageBar
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.pageBar
import io.github.zenthxsin.rustwright.ui.kit.OwnHeader
import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Shapes
import arc.scene.Element
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.pic.imaging.PixelArtProcessor
import io.github.zenthxsin.rustwright.pic.imaging.PngEncoder
import io.github.zenthxsin.rustwright.pic.imaging.RgbaImage
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.ImageThumb
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import io.github.zenthxsin.rustwright.ui.kit.SheetPage
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.ToolRequests
import io.github.zenthxsin.rustwright.ui.kit.chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 调色板（「更多 → 调色板」）：把一张图量化成 4 / 8 / 16 / 32 色，列出每种颜色和占比；
 * 点色块可以换成阵营色（保留明暗），「存为新图」写进项目 images/。
 */
class PaletteToolPage(private val project: () -> ProjectContext?, private val scope: CoroutineScope) : Table(), OwnHeader {
    private var source: File? = null
    private var decoded: RgbaImage? = null
    private var result: RgbaImage? = null
    private var colors = 8
    private val replaced = LinkedHashMap<Int, Int>()
    private val preview = RgbaPreview()
    private val chips = Table()
    private val swatches = Table().top().left()
    private val status = K.text("", 13f, K.subtle, lh = 13f).apply { setEllipsis(true) }
    private var job: Job? = null

    private val bar = PageBar("调色板") { status.text.toString() }
    override fun bindBack(back: () -> Unit) = bar.bindBack(back)

    init {
        top()
        pageBar(bar)
        bar.action(PI.img, "选图") { pickSource() }
        bar.action(PI.save, "存为新图") { save() }
        bar.action(PI.chat, "交给${io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.assistant}") { askRust() }
        val col = Table().top()
        col.margin(dp(4f), dp(20f), dp(24f), dp(20f))
        val pv = Tap(null, 12f) { pickSource() }
        pv.name = "换图"
        pv.add(preview).grow()
        col.add(pv).growX().height(dp(260f)).row()
        col.add(K.text("颜色数", 15f, K.text, lh = 15f)).left().padTop(dp(18f)).row()
        col.add(chips).growX().padTop(dp(10f)).row()
        val sh = Table(); sh.add(K.text("调色板", 15f, K.text, lh = 15f)).left(); sh.add(K.text("点色块换成阵营色", 13f, K.subtle, lh = 13f)).left().padLeft(dp(8f)); sh.add().growX()
        col.add(sh).growX().padTop(dp(18f)).row()
        col.add(swatches).growX().padTop(dp(10f)).row()
        add(ScrollPane(col).apply { setScrollingDisabledX(true); setOverscroll(false, true) }).grow()
        buildChips()
        load(ToolRequests.take("palette") ?: project()?.images()?.firstOrNull())
    }

    private fun buildChips() {
        chips.clearChildren()
        listOf(4, 8, 16, 32).forEachIndexed { i, n ->
            chips.add(chip("$n 色", n == colors) { colors = n; replaced.clear(); buildChips(); process() })
                .growX().uniformX().padLeft(if (i > 0) dp(4f) else 0f).padRight(if (i < 3) dp(4f) else 0f)
        }
    }

    private fun load(f: File?) {
        source = f
        decoded = null; result = null; replaced.clear()
        preview.set(null); swatches.clearChildren()
        status.setText(f?.name ?: "项目里还没有图片")
        if (f == null) return
        scope.launch(Dispatchers.IO) {
            val img = runCatching { ArcImageDecoder.decode(f.readBytes()) }.getOrNull()
            Core.app.post { decoded = img; if (img == null) status.setText("读不了这张图：${f.name}") else process() }
        }
    }

    private fun process() {
        val src = decoded ?: return
        job?.cancel()
        val n = colors
        val swaps = HashMap(replaced)
        job = scope.launch(Dispatchers.Default) {
            val q = PixelArtProcessor.quantizePalette(src, n)
            val out = if (swaps.isEmpty()) q else RgbaImage(q.width, q.height, IntArray(q.pixels.size) { i -> val c = q.pixels[i]; swaps[c]?.let { r -> (c and 0xFF000000.toInt()) or (r and 0xFFFFFF) } ?: c })
            val counts = HashMap<Int, Int>()
            var opaque = 0
            for (c in q.pixels) if ((c ushr 24) >= 128) { counts[c] = (counts[c] ?: 0) + 1; opaque++ }
            val sorted = counts.entries.sortedByDescending { it.value }
            Core.app.post {
                result = out
                preview.set(out)
                status.setText("${source?.name} · ${src.width}×${src.height} · ${sorted.size} 色")
                swatches.clearChildren()
                sorted.forEachIndexed { i, (c, k) -> swatches.add(swatch(c, swaps[c], k * 100f / opaque.coerceAtLeast(1))).growX().uniformX().pad(dp(4f)); if (i % 4 == 3) swatches.row() }
            }
        }
    }

    private fun swatch(original: Int, now: Int?, pct: Float): Element {
        val shown = now?.let { (original and 0xFF000000.toInt()) or (it and 0xFFFFFF) } ?: original
        val t = Tap(null, 10f) { pickReplacement(original) }
        t.name = hex(original)
        t.add(ColorBox(argb(shown), now != null)).growX().height(dp(44f)).row()
        t.add(K.mono(hex(shown), 11f, K.muted, 13f)).padTop(dp(4f)).row()
        t.add(K.text("%.0f%%".format(pct), 11f, K.subtle, lh = 11f)).padTop(dp(2f))
        return t
    }

    /** 阵营色：换色时保留原色的明暗（HSV 的 V），只换色相和饱和度。 */
    private fun pickReplacement(original: Int) {
        val ctx = project() ?: return
        val g = ListGroup()
        val presets = listOf("红" to 0xE04A3A, "蓝" to 0x3A7BD5, "绿" to 0x49A84A, "黄" to 0xE3B23C, "紫" to 0x8E5BD6, "橙" to 0xE07B39, "青" to 0x3AB5B0, "灰" to 0x8A8A86)
        if (replaced.containsKey(original)) g.row(row("还原", null) { replaced.remove(original); ctx.closeSheet(); process() })
        for ((name, rgb) in presets) {
            val v = argb(original).let { maxOf(it.r, it.g, it.b) }
            val hsv = FloatArray(3); Color(rgb shl 8 or 0xFF).toHsv(hsv)
            val c = Color().fromHsv(hsv[0], hsv[1], v.coerceAtLeast(0.15f))
            val packed = ((c.r * 255).toInt() shl 16) or ((c.g * 255).toInt() shl 8) or (c.b * 255).toInt()
            g.row(row(name, packed) { replaced[original] = packed; ctx.closeSheet(); process() })
        }
        ctx.sheet(SheetPage("换成阵营色", Table().top().apply { add(g).growX().pad(0f, dp(10f), dp(20f), dp(10f)) }, height = 540f))
    }

    private fun row(label: String, rgb: Int?, onClick: () -> Unit) = Tap(K.surface, 0f, onClick).apply {
        name = label
        margin(dp(12f), dp(18f), dp(12f), dp(18f))
        if (rgb != null) add(ColorBox(argb(0xFF000000.toInt() or rgb), false)).size(dp(28f)).padRight(dp(14f))
        add(K.text(label, 16f, K.text)).left().growX()
        if (rgb != null) add(K.mono("#%06X".format(rgb), 12f, K.subtle))
    }

    private fun save() {
        val ctx = project() ?: return
        val img = result ?: return ctx.notify("还没有处理好的图")
        val dir = ctx.projectDir() ?: return ctx.notify("先选一个项目")
        val name = (source?.nameWithoutExtension ?: "image") + "_${colors}c.png"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { File(dir, "images/$name").apply { parentFile.mkdirs(); writeBytes(PngEncoder.encode(img)) } } }
            Core.app.post { r.fold({ ctx.notify("已存到产物：images/$name") }, { ctx.notify("保存失败：${it.message}") }) }
        }
    }

    private fun askRust() {
        val ctx = project() ?: return
        val src = source?.let { f -> ctx.projectDir()?.let { d -> f.relativeToOrNull(d)?.path } ?: f.name }
        ctx.askRust(if (src != null) "帮我把 $src 的配色统一成阵营色，控制在 $colors 色以内" else "帮我统一一下当前项目贴图的阵营配色")
    }

    private fun pickSource() {
        val ctx = project() ?: return
        val imgs = ctx.images()
        if (imgs.isEmpty()) return ctx.notify("项目里还没有图片")
        val g = ListGroup()
        for (f in imgs.take(40)) {
            val row = Tap(K.surface, 0f) { ctx.closeSheet(); load(f) }
            row.name = f.name
            row.margin(dp(10f), dp(16f), dp(10f), dp(16f))
            row.add(Table(K.fill(K.input, 8f)).apply { clipRadius = dp(8f); add(ImageThumb(f)).grow() }).size(dp(44f)).padRight(dp(14f))
            row.add(K.text(f.name, 16f, K.text, lh = 16f).apply { setEllipsis(true) }).growX().minWidth(0f).left()
            g.row(row)
        }
        ctx.sheet(SheetPage("选择图片", Table().top().apply { add(g).growX() }, height = 540f))
    }

    private fun argb(c: Int) = Color(((c and 0xFFFFFF) shl 8) or 0xFF)
    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    private class ColorBox(private val c: Color, private val marked: Boolean) : Element() {
        override fun draw() {
            val a = parentAlpha * color.a
            Shapes.rect(x, y, width, height, dp(8f), Color(c).a(a), dp(1f), Color(if (marked) K.accent else K.inputBorder).a(a))
        }
    }
}
