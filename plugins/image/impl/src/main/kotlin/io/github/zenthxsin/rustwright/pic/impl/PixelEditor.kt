package io.github.zenthxsin.rustwright.pic.impl

import arc.graphics.Color
import arc.graphics.Pixmap
import arc.graphics.Texture
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.graphics.g2d.Lines
import arc.graphics.g2d.TextureRegion
import arc.input.KeyCode
import arc.scene.Element
import arc.scene.Scene
import arc.scene.event.InputEvent
import arc.scene.event.InputListener
import arc.scene.ui.Label
import arc.scene.ui.ScrollPane
import arc.scene.ui.TextButton
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.pic.imaging.PixelDocument
import io.github.zenthxsin.rustwright.pic.imaging.PngEncoder
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import io.github.zenthxsin.rustwright.ui.designsystem.BackHandler
import io.github.zenthxsin.rustwright.ui.designsystem.Icons
import io.github.zenthxsin.rustwright.ui.designsystem.Rw
import io.github.zenthxsin.rustwright.ui.designsystem.RwButton
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import io.github.zenthxsin.rustwright.ui.widgets.HorizontalScroll
import io.github.zenthxsin.rustwright.ui.widgets.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import io.github.zenthxsin.rustwright.pic.imaging.Color as Argb

/** 像素编辑工具。 */
enum class PixelTool(val label: String) { PEN("笔"), ERASER("橡皮"), FILL("填充"), PICK("吸管"), PAN("移动") }

/** 画布：按缩放绘制像素和网格，单指按当前工具作画；移动工具下拖动平移。 */
class PixelCanvas(val doc: PixelDocument) : Element() {
    var tool = PixelTool.PEN
    var color: Int = Argb.BLACK
    var brush = 1
    var showGrid = true
    var zoom = 0f
    var offsetX = 0f
    var offsetY = 0f
    var onPicked: (Int) -> Unit = {}

    private var texture: Texture? = null
    private var pixmap: Pixmap? = null
    private var uploaded = -1L
    private var last: Pair<Int, Int>? = null

    init {
        name = "像素画布"
        touchable = arc.scene.event.Touchable.enabled
        addListener(object : InputListener() {
            var px = 0f; var py = 0f
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: KeyCode): Boolean {
                if (pointer > 0) return false
                px = x; py = y
                if (tool != PixelTool.PAN && tool != PixelTool.PICK) doc.beginStroke()
                apply(x, y, first = true)
                return true
            }
            override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                if (tool == PixelTool.PAN) { offsetX += x - px; offsetY += y - py; px = x; py = y } else apply(x, y, first = false)
            }
            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: KeyCode) { last = null }
        })
    }

    private fun cell(x: Float, y: Float): Pair<Int, Int> {
        val (ox, oy) = origin()
        return ((x - ox) / zoom).toInt().let { if (x < ox) -1 else it } to (doc.height - 1 - ((y - oy) / zoom).toInt()).let { if (y < oy) doc.height else it }
    }

    private fun apply(x: Float, y: Float, first: Boolean) {
        val (cx, cy) = cell(x, y)
        when (tool) {
            PixelTool.PEN, PixelTool.ERASER -> {
                val c = if (tool == PixelTool.ERASER) Argb.TRANSPARENT else color
                val prev = last
                if (prev == null || first) doc.plot(cx, cy, c, brush) else doc.line(prev.first, prev.second, cx, cy, c, brush)
                last = cx to cy
            }
            PixelTool.FILL -> if (first) doc.fill(cx, cy, color)
            PixelTool.PICK -> if (doc.inBounds(cx, cy)) { color = doc.get(cx, cy); onPicked(color) }
            PixelTool.PAN -> Unit
        }
    }

    fun fit() {
        zoom = minOf(width / doc.width, height / doc.height).coerceAtLeast(1f) * 0.92f
        offsetX = 0f; offsetY = 0f
    }

    private fun origin(): Pair<Float, Float> =
        (width - doc.width * zoom) / 2f + offsetX to (height - doc.height * zoom) / 2f + offsetY

    override fun sizeChanged() { super.sizeChanged(); if (zoom <= 0f && width > 0) fit() }

    override fun draw() {
        validate()
        if (zoom <= 0f) fit()
        upload()
        val (ox0, oy0) = origin()
        val ox = x + ox0; val oy = y + oy0
        val w = doc.width * zoom; val h = doc.height * zoom
        // 透明棋盘格
        val check = (zoom * 4).coerceAtLeast(8f)
        var cy = 0f
        var row = 0
        while (cy < h) {
            var cx = 0f; var col = row
            while (cx < w) {
                Draw.color(if (col % 2 == 0) LIGHT else DARK)
                Fill.crect(ox + cx, oy + cy, minOf(check, w - cx), minOf(check, h - cy))
                cx += check; col++
            }
            cy += check; row++
        }
        Draw.color()
        texture?.let { Draw.rect(TextureRegion(it), ox + w / 2f, oy + h / 2f, w, h) }
        if (showGrid && zoom >= 6f) {
            Draw.color(0f, 0f, 0f, 0.25f)
            Lines.stroke(1f)
            for (i in 0..doc.width) Lines.line(ox + i * zoom, oy, ox + i * zoom, oy + h)
            for (j in 0..doc.height) Lines.line(ox, oy + j * zoom, ox + w, oy + j * zoom)
        }
        Draw.reset()
    }

    private fun upload() {
        if (uploaded == doc.revision && texture != null && pixmap?.width == doc.width && pixmap?.height == doc.height) return
        if (pixmap?.width != doc.width || pixmap?.height != doc.height) {
            texture?.dispose(); pixmap?.dispose()
            pixmap = Pixmap(doc.width, doc.height)
            texture = null
        }
        val pix = pixmap!!
        val src = doc.image.pixels
        for (yy in 0 until doc.height) for (xx in 0 until doc.width) {
            val c = src[yy * doc.width + xx]
            pix.set(xx, yy, (c shl 8) or (c ushr 24)) // ARGB → RGBA
        }
        val t = texture
        if (t == null) texture = Texture(pix).apply { setFilter(Texture.TextureFilter.nearest) } else t.draw(pix)
        uploaded = doc.revision
    }

    fun dispose() { texture?.dispose(); pixmap?.dispose(); texture = null; pixmap = null }

    companion object {
        // 透明棋盘格，跟随主题深浅
        private val checkerDark = Color.valueOf("3a3f4a") to Color.valueOf("2c3038")
        private val checkerLight = Color.valueOf("e4e6ea") to Color.valueOf("f3f4f6")
        private val LIGHT get() = (if (RwTheme.dark) checkerDark else checkerLight).first
        private val DARK get() = (if (RwTheme.dark) checkerDark else checkerLight).second
    }
}

/**
 * 像素编辑器面板：工具、笔刷、镜像、网格、撤销重做、调色板（常用色 + 图中已用色 + 自定义十六进制），保存回 PNG。
 */
class PixelEditorPanel(
    private val file: File,
    private val scope: CoroutineScope,
    private val onBack: (() -> Unit)? = null,
    private val writePng: suspend (File, ByteArray) -> Unit = { target, bytes -> writePixelPng(target, bytes) },
    private val onClose: (saved: Boolean) -> Unit,
) : Table(), BackHandler, io.github.zenthxsin.rustwright.ui.kit.OwnHeader {

    /** 自己有顶栏（文件名、撤销 / 重做、保存），外壳的统一顶栏收起；返回走构造时传入的 [onBack]。 */
    override fun bindBack(back: () -> Unit) {}

    private val doc = PixelDocument(ArcImageDecoder.decode(file.readBytes()))
    private val canvas = PixelCanvas(doc)
    private val paletteRow = Table().left()
    private var savedRevision = doc.revision
    private var savedOnce = false
    private var saving = false
    private var confirming = false
    private var attachment = 0L
    private val swatch = Table()

    init {
        top()
        add(Rw.topBar(
            file.name,
            titleElement = Table().left().apply {
                add(Rw.title(file.name)).left().growX().minWidth(0f).row()
                add(Rw.caption("").apply {
                    setEllipsis(true)
                    update { setText("${doc.width}×${doc.height} · " + if (saving) "保存中…" else if (doc.revision != savedRevision) "未保存" else "已保存") }
                }).left().growX().minWidth(0f)
            },
            onBack = { onBack?.invoke() ?: onBackRequested {} },
            actions = listOf(
                Rw.icon(Icons.undo, "撤销") { doc.undo() }.apply { update { isDisabled = !doc.canUndo } },
                Rw.icon(Icons.redo, "重做") { doc.redo() }.apply { update { isDisabled = !doc.canRedo } },
                Rw.primary("保存") { save() }.apply { update { isDisabled = saving || doc.revision == savedRevision } },
            ),
        ).apply { background(RwTheme.solid(RwTheme.Colors.surface)) }).growX().row()
        add(Rw.divider()).growX().height(dp(1f)).row()
        add(arc.scene.ui.layout.Stack(canvas, Table().top().right().apply {
            touchable = arc.scene.event.Touchable.childrenOnly
            add(Table(RwTheme.card(radiusDp = 12)).apply {
                add(Rw.icon(Icons.add, "放大") { canvas.zoom *= 1.5f }).row()
                add(Rw.icon('\ue15b', "缩小") { canvas.zoom = (canvas.zoom / 1.5f).coerceAtLeast(1f) }).row()
                add(Rw.icon(Icons.fit, "适应") { canvas.fit() })
            }).pad(dp(9f))
        })).grow().row()
        add(Rw.divider()).growX().height(dp(1f)).row()
        add(Table(RwTheme.solid(RwTheme.Colors.surface)).apply {
            margin(dp(8f), dp(9f), dp(8f), dp(9f))
            add(HorizontalScroll(Table().left().apply {
                PixelTool.entries.forEach { t ->
                    add(RwButton(t.label, toolIcon(t), RwTheme.Styles.chip).apply {
                        margin(0f, dp(9f), 0f, dp(8f))
                        update { isChecked = canvas.tool == t }
                        clicked { canvas.tool = t }
                    }).height(dp(30f)).padRight(dp(5f))
                }
                add(RwButton("", Icons.brush, RwTheme.Styles.chip).apply {
                    margin(0f, dp(9f), 0f, dp(8f))
                    update { setText("笔刷${canvas.brush}") }
                    clicked { canvas.brush = if (canvas.brush >= 4) 1 else canvas.brush + 1 }
                }).height(dp(30f)).padRight(dp(5f))
                add(RwButton("镜像", Icons.flip, RwTheme.Styles.chip).apply {
                    margin(0f, dp(9f), 0f, dp(8f))
                    update { isChecked = doc.mirrorX }
                    clicked { doc.mirrorX = !doc.mirrorX }
                }).height(dp(30f)).padRight(dp(5f))
                add(RwButton("网格", Icons.grid, RwTheme.Styles.chip).apply {
                    margin(0f, dp(9f), 0f, dp(8f))
                    update { isChecked = canvas.showGrid }
                    clicked { canvas.showGrid = !canvas.showGrid }
                }).height(dp(30f)).padRight(dp(5f))
                add(RwButton("水平翻转", Icons.flip, RwTheme.Styles.chip).apply {
                    margin(0f, dp(9f), 0f, dp(8f))
                    clicked { doc.flipHorizontal() }
                }).height(dp(30f))
            })).growX().row()
            add(Table().left().apply {
                add(swatch).size(dp(32f)).padRight(dp(8f))
                add(HorizontalScroll(paletteRow)).growX().minWidth(0f)
                add(Rw.icon(Icons.palette, "自定义颜色") { customColor() }).padLeft(dp(5f))
            }).growX().padTop(dp(8f))
        }).growX()
        canvas.onPicked = { refreshSwatch(); canvas.tool = PixelTool.PEN }
        rebuildPalette()
        refreshSwatch()
    }

    private fun toolIcon(t: PixelTool): Char = when (t) {
        PixelTool.PEN -> Icons.edit
        PixelTool.ERASER -> Icons.eraser
        PixelTool.FILL -> Icons.fill
        PixelTool.PICK -> Icons.picker
        PixelTool.PAN -> Icons.move
    }

    private fun refreshSwatch() {
        swatch.background = RwTheme.card(argbToColor(canvas.color), RwTheme.Colors.outline, 8)
    }

    private fun rebuildPalette() {
        paletteRow.clearChildren()
        (BASE + doc.palette(16)).distinct().forEach { c ->
            paletteRow.add(Table(RwTheme.card(argbToColor(c), RwTheme.Colors.outline, 8)).apply {
                name = "颜色:%08X".format(c)
                touchable = arc.scene.event.Touchable.enabled
                clicked { canvas.color = c; refreshSwatch(); if (canvas.tool == PixelTool.ERASER || canvas.tool == PixelTool.PICK) canvas.tool = PixelTool.PEN }
            }).size(dp(26f)).padRight(dp(5f))
        }
    }

    private fun customColor() {
        scope.launch {
            val hex = Dialogs.input("颜色", "RRGGBB 或 AARRGGBB", "%08X".format(canvas.color))?.trim()?.removePrefix("#") ?: return@launch
            val v = hex.toLongOrNull(16) ?: return@launch withContext(io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher) { Toasts.show("颜色格式不对") }
            val argb = if (hex.length <= 6) (0xFF000000 or v).toInt() else v.toInt()
            withContext(io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher) { canvas.color = argb; refreshSwatch() }
        }
    }

    override fun setScene(stage: Scene?) {
        if (scene !== stage) {
            attachment++
            if (stage == null) canvas.dispose()
        }
        super.setScene(stage)
    }

    private fun attachedTo(expected: Long) = scene != null && attachment == expected

    private fun save(then: (() -> Unit)? = null) {
        if (saving) return
        // Encoding and I/O may suspend while the user edits or visits another page.
        val snapshot = doc.image.copy()
        val rev = doc.revision
        val expected = attachment
        saving = true
        scope.launch(ArcDispatcher) {
            try {
                withContext(Dispatchers.IO) { writePng(file, PngEncoder.encode(snapshot)) }
                savedRevision = rev
                savedOnce = true
                if (attachedTo(expected)) {
                    rebuildPalette()
                    Toasts.show("已保存")
                    if (doc.revision == rev) then?.invoke()
                    else if (then != null) Toasts.show("保存期间有新修改，请再次保存后返回")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attachedTo(expected)) Toasts.show("保存失败：${e.message}")
            } finally {
                saving = false
            }
        }
    }

    override fun onBackRequested(leave: () -> Unit): Boolean {
        if (saving) { Toasts.show("正在保存图片，请稍候"); return true }
        if (doc.revision == savedRevision) { finish(leave); return true }
        if (confirming) return true
        confirming = true
        val expected = attachment
        scope.launch(ArcDispatcher) {
            try {
                val i = Dialogs.choose("尚未保存", "${file.name} 的像素修改还没保存。", listOf("取消", "放弃", "保存"))
                if (attachedTo(expected)) when (i) {
                    1 -> finish(leave)
                    2 -> save { finish(leave) }
                }
            } finally {
                confirming = false
            }
        }
        return true
    }

    private fun finish(leave: () -> Unit) { onClose(savedOnce); leave() }

    companion object {
        private fun argbToColor(c: Int) = Color((c shl 8) or (c ushr 24))
        private val BASE = listOf(
            0xFF000000, 0xFFFFFFFF, 0xFF7F7F7F, 0xFFC3C3C3, 0xFF880015, 0xFFED1C24, 0xFFFF7F27, 0xFFFFF200,
            0xFF22B14C, 0xFF00A2E8, 0xFF3F48CC, 0xFFA349A4, 0xFFB97A57, 0xFF5B6E3A, 0xFF4A5A6A, 0x00000000,
        ).map { it.toInt() }
    }
}

/** Replace only after a complete PNG has been written, retaining the original on failure. */
private fun writePixelPng(file: File, bytes: ByteArray) {
    val target = file.canonicalFile
    val temp = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
    try {
        FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temp.delete()
    }
}
