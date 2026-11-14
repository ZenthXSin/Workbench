package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.ui.kit.PageBar
import io.github.zenthxsin.rustwright.ui.kit.pageBar
import io.github.zenthxsin.rustwright.ui.kit.OwnHeader
import arc.Core
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.pic.imaging.PngEncoder
import io.github.zenthxsin.rustwright.pic.imaging.RgbaImage
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.ImageThumb
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import io.github.zenthxsin.rustwright.ui.kit.SheetPage
import io.github.zenthxsin.rustwright.ui.kit.Slider
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 切图与拼图（「更多 → 切图与拼图」）：
 * - 拆分：按列 × 行把一张精灵表切成单帧，存到 images/<名>_frames/
 * - 拼合：选几张帧，按列数拼成精灵表（精灵动画可用横排一行），存到 images/
 */
class SliceToolPage(private val project: () -> ProjectContext?, private val scope: CoroutineScope) : Table(), OwnHeader {
    private var split = true
    private var source: File? = null
    private var sheet: RgbaImage? = null
    private var cols = 4
    private var rows = 1
    private val frames = ArrayList<File>()
    private var joinCols = 0
    private var joined: RgbaImage? = null
    private val modeRow = Table()
    private val body = Table().top()
    private val preview = RgbaPreview()
    private val info = K.text("", 13f, K.subtle, lh = 13f).apply { setEllipsis(true) }

    private val bar = PageBar("切图与拼图") { info.text.toString() }
    override fun bindBack(back: () -> Unit) = bar.bindBack(back)

    init {
        top()
        pageBar(bar)
        bar.action(PI.img, "选图") { if (split) pickSheet() else pickFrames() }
        val col = Table().top()
        col.margin(dp(4f), dp(20f), dp(24f), dp(20f))
        col.add(modeRow).growX().row()
        val pv = Tap(null, 12f) { if (split) pickSheet() else pickFrames() }
        pv.name = "选图"
        pv.add(preview).grow()
        col.add(pv).growX().height(dp(240f)).padTop(dp(14f)).row()
        col.add(body).growX().row()
        add(ScrollPane(col).apply { setScrollingDisabledX(true); setOverscroll(false, true) }).grow()
        rebuild()
        (io.github.zenthxsin.rustwright.ui.kit.ToolRequests.take("slice") ?: project()?.images()?.firstOrNull())?.let { loadSheet(it) }
    }

    private fun rebuild() {
        modeRow.clearChildren()
        modeRow.add(chip("拆分精灵表", split) { if (!split) { split = true; preview.set(sheet); rebuild() } }).growX().uniformX().padRight(dp(4f))
        modeRow.add(chip("拼合成精灵表", !split) { if (split) { split = false; preview.set(joined); rebuild() } }).growX().uniformX().padLeft(dp(4f))
        preview.gridCols = if (split) cols else 0
        preview.gridRows = if (split) rows else 0

        body.clearChildren()
        if (split) {
            val s = sheet
            info.setText(if (s == null) "点上面选一张精灵表" else "${source?.name} · ${s.width}×${s.height} · 每帧 ${s.width / cols}×${s.height / rows}")
            val btnLabel = K.text("拆成 ${cols * rows} 帧", 16f, K.onLight, 600)
            // 拖动滑条时只改文字和网格，不重建（重建会把正在拖的滑条换掉）
            fun light() {
                preview.gridCols = cols; preview.gridRows = rows
                btnLabel.setText("拆成 ${cols * rows} 帧")
                sheet?.let { s2 -> info.setText("${source?.name} · ${s2.width}×${s2.height} · 每帧 ${s2.width / cols}×${s2.height / rows}") }
            }
            fun slider(label: String, value: Int, set: (Int) -> Unit) {
                val v = K.text("$value", 15f, K.muted, lh = 15f)
                val h = Table(); h.add(K.text(label, 15f, K.text, lh = 15f)).left().growX(); h.add(v)
                body.add(h).growX().padTop(dp(18f)).row()
                body.add(Slider(1f, 16f, value.toFloat()) { set(it.toInt()); v.setText("${it.toInt()}"); light() }).growX().height(dp(24f)).padTop(dp(10f)).row()
            }
            slider("列数", cols) { cols = it }
            slider("行数", rows) { rows = it }
            body.add(Tap(K.light, 12f) { doSplit() }.apply { name = "拆成单帧"; add(btnLabel) }).growX().height(dp(48f)).padTop(dp(22f)).row()
        } else {
            info.setText(if (frames.isEmpty()) "点上面选几张帧（按选择顺序拼）" else "${frames.size} 帧" + (joined?.let { " · 精灵表 ${it.width}×${it.height}" } ?: ""))
            val list = ListGroup()
            frames.forEachIndexed { i, f ->
                val r = Table(K.fill(K.surface, 0f))
                r.margin(dp(8f), dp(14f), dp(8f), dp(10f))
                r.add(K.text("${i + 1}", 14f, K.subtle)).width(dp(24f))
                r.add(Table(K.fill(K.input, 6f)).apply { clipRadius = dp(6f); add(ImageThumb(f)).grow() }).size(dp(36f)).padRight(dp(12f))
                r.add(K.text(f.name, 15f, K.text, lh = 15f).apply { setEllipsis(true) }).growX().minWidth(0f).left()
                r.add(Tap(null, 16f) { frames.removeAt(i); join() }.apply { name = "移除"; add(K.svg(PI.close, 16f, K.subtle, 2f)) }).size(dp(32f))
                list.row(r)
            }
            if (frames.isNotEmpty()) body.add(list).growX().padTop(dp(14f)).row()
            fun perLabel() = (if (joinCols <= 0) frames.size.coerceAtLeast(1) else joinCols).let { n -> if (joinCols <= 0) "一行（$n）" else "$n" }
            val v = K.text(perLabel(), 15f, K.muted, lh = 15f)
            val h = Table(); h.add(K.text("每行几帧", 15f, K.text, lh = 15f)).left().growX(); h.add(v)
            body.add(h).growX().padTop(dp(18f)).row()
            body.add(Slider(0f, 16f, joinCols.toFloat()) { joinCols = it.toInt(); v.setText(perLabel()); join(rebuildAfter = false) }).growX().height(dp(24f)).padTop(dp(10f)).row()
            val b = Table()
            b.add(Tap(null, 12f) { pickFrames() }.apply { name = "添加帧"; border = K.inputBorder; add(K.text("添加帧", 16f, K.text)) }).growX().uniformX().height(dp(48f)).padRight(dp(5f))
            b.add(Tap(K.light, 12f) { saveJoined() }.apply { name = "存为精灵表"; add(K.text("存为精灵表", 16f, K.onLight, 600)) }).growX().uniformX().height(dp(48f)).padLeft(dp(5f))
            body.add(b).growX().padTop(dp(22f)).row()
            body.add(K.para("精灵表由等大的动画帧组成；按目标格式选择列数与帧顺序。", 13f, K.subtle, lh = 19f)).growX().left().padTop(dp(12f))
        }
    }

    private fun decode(f: File): RgbaImage? = runCatching { ArcImageDecoder.decode(f.readBytes()) }.getOrNull()

    private fun loadSheet(f: File) {
        source = f
        scope.launch(Dispatchers.IO) {
            val img = decode(f)
            Core.app.post { sheet = img; if (split) preview.set(img); rebuild() }
        }
    }

    private fun doSplit() {
        val ctx = project() ?: return
        val s = sheet ?: return ctx.notify("先选一张精灵表")
        val dir = ctx.projectDir() ?: return ctx.notify("先选一个项目")
        val fw = s.width / cols; val fh = s.height / rows
        if (fw < 1 || fh < 1) return ctx.notify("切得太细了")
        val base = source?.nameWithoutExtension ?: "sheet"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val out = File(dir, "images/${base}_frames").apply { mkdirs() }
                    var k = 0
                    for (ry in 0 until rows) for (cx in 0 until cols) {
                        val f = RgbaImage(fw, fh)
                        for (y in 0 until fh) System.arraycopy(s.pixels, (ry * fh + y) * s.width + cx * fw, f.pixels, y * fw, fw)
                        File(out, "%s_%02d.png".format(base, ++k)).writeBytes(PngEncoder.encode(f))
                    }
                    k
                }
            }
            Core.app.post { r.fold({ ctx.notify("已拆成 $it 帧：images/${base}_frames/") }, { ctx.notify("拆分失败：${it.message}") }) }
        }
    }

    private fun join(rebuildAfter: Boolean = true) {
        val fs = frames.toList()
        if (fs.isEmpty()) { joined = null; preview.set(null); rebuild(); return }
        val per = if (joinCols <= 0) fs.size else joinCols
        scope.launch(Dispatchers.IO) {
            val imgs = fs.mapNotNull { decode(it) }
            if (imgs.isEmpty()) return@launch
            val fw = imgs.maxOf { it.width }; val fh = imgs.maxOf { it.height }
            val c = minOf(per, imgs.size); val r = (imgs.size + c - 1) / c
            val out = RgbaImage(fw * c, fh * r)
            imgs.forEachIndexed { i, im ->
                val ox = (i % c) * fw + (fw - im.width) / 2; val oy = (i / c) * fh + (fh - im.height) / 2
                for (y in 0 until im.height) System.arraycopy(im.pixels, y * im.width, out.pixels, (oy + y) * out.width + ox, im.width)
            }
            Core.app.post {
                joined = out
                if (!split) preview.set(out)
                if (rebuildAfter) rebuild() else info.setText("${fs.size} 帧 · 精灵表 ${out.width}×${out.height}")
            }
        }
    }

    private fun saveJoined() {
        val ctx = project() ?: return
        val img = joined ?: return ctx.notify("先添加几帧")
        val dir = ctx.projectDir() ?: return ctx.notify("先选一个项目")
        val name = (frames.firstOrNull()?.nameWithoutExtension?.substringBeforeLast('_') ?: "frames") + "_sheet.png"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { File(dir, "images/$name").apply { parentFile.mkdirs(); writeBytes(PngEncoder.encode(img)) } } }
            Core.app.post { r.fold({ ctx.notify("已存到产物：images/$name（${frames.size} 帧）") }, { ctx.notify("保存失败：${it.message}") }) }
        }
    }

    private fun imageSheet(title: String, pick: (File) -> Unit) {
        val ctx = project() ?: return
        val imgs = ctx.images()
        if (imgs.isEmpty()) return ctx.notify("项目里还没有图片")
        val g = ListGroup()
        for (f in imgs.take(60)) {
            val row = Tap(K.surface, 0f) { pick(f) }
            row.name = f.name
            row.margin(dp(10f), dp(16f), dp(10f), dp(16f))
            row.add(Table(K.fill(K.input, 8f)).apply { clipRadius = dp(8f); add(ImageThumb(f)).grow() }).size(dp(44f)).padRight(dp(14f))
            row.add(K.text(f.name, 16f, K.text, lh = 16f).apply { setEllipsis(true) }).growX().minWidth(0f).left()
            g.row(row)
        }
        ctx.sheet(SheetPage(title, Table().top().apply { add(g).growX() }, height = 540f))
    }

    private fun pickSheet() = imageSheet("选择精灵表") { f -> project()?.closeSheet(); loadSheet(f) }

    // 拼合：每点一张就加进来（弹层不关，可以连续选），关掉后看结果
    private fun pickFrames() = imageSheet("点选要拼的帧") { f -> frames += f; project()?.notify("已加入第 ${frames.size} 帧"); join() }
}
