package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.ui.kit.PageBar
import io.github.zenthxsin.rustwright.ui.kit.pageBar
import io.github.zenthxsin.rustwright.ui.kit.OwnHeader
import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Shapes
import arc.scene.ui.ScrollPane
import arc.scene.ui.TextArea
import arc.scene.ui.TextField
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.pic.api.CreationMode
import io.github.zenthxsin.rustwright.pic.api.PicImage
import io.github.zenthxsin.rustwright.pic.api.PicRequest
import io.github.zenthxsin.rustwright.pic.api.PicService
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.ImageThumb
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import io.github.zenthxsin.rustwright.ui.kit.SheetPage
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.chip
import io.github.zenthxsin.rustwright.ui.kit.pulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 生图（「更多 → 生图」），按像素化页的写法：提示词框、类型 / 尺寸 / 张数 chip、参考图、
 * 「生成」「交给助手」，下面是这一页生成的结果（两列），每张可「存到项目」。
 */
class ImageGenToolPage(
    private val project: () -> ProjectContext?,
    private val service: PicService,
    private val model: () -> String?,
    private val scope: CoroutineScope,
) : Table(), OwnHeader {
    private var mode = CreationMode.GAME_SPRITE
    private var size = 64
    private var count = 1
    private var free = service.selectedFree
    private var freeModel: String? = null
    private var loadingFree = false
    private val refs = ArrayList<File>()
    private val results = ArrayList<PicImage>()
    private var job: Job? = null
    private var error: String? = null

    private val prompt = TextArea("", TextField.TextFieldStyle().apply {
        font = RwTheme.text(16f, 400)
        fontColor = K.text
        messageFont = RwTheme.text(16f, 400)
        messageFontColor = K.subtle
        cursor = RwTheme.solid(K.text).also { it.minWidth = dp(1.5f) }
        selection = RwTheme.solid(K.blue.cpy().a(0.3f))
    }).apply {
        setMessageText("描述画面，比如：俯视角的重型坦克，铁灰配色，炮塔朝上")
        setPrefRows(4f)
    }
    private val modeChips = Table()
    private val routeChips = Table()
    private val sizeBlock = Table()
    private val sizeChips = Table()
    private val countChips = Table()
    private val refRow = Table().left()
    private val refHeading = Table()
    private val status = Table()
    private val grid = Table().top().left()
    private val generate = GenerateButton()

    private val bar = PageBar("生图") { if (free) "免费线路" else (model() ?: "未选生图模型") }
    override fun bindBack(back: () -> Unit) = bar.bindBack(back)

    init {
        top()
        pageBar(bar)
        bar.action(PI.chat, "交给${io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.assistant}") { askRust() }
        // 和像素化一致：padding 4 20 24，块间距 18，标题 15px
        val col = Table().top()
        col.margin(dp(4f), dp(20f), dp(24f), dp(20f))

        val box = object : Table() {
            override fun drawBackground(x: Float, y: Float) {
                val a = color.a * parentAlpha
                Shapes.rect(x, y, width, height, dp(12f), Color(K.input).a(a), dp(1f), Color(K.inputBorder).a(a))
            }
        }
        box.add(prompt).growX().minHeight(dp(104f)).pad(dp(10f), dp(14f), dp(10f), dp(14f))
        col.add(box).growX().row()

        col.add(head("生图线路")).growX().padTop(dp(18f)).row()
        col.add(routeChips).growX().padTop(dp(10f)).row()

        col.add(head("类型")).growX().padTop(dp(18f)).row()
        col.add(modeChips).growX().padTop(dp(10f)).row()
        col.add(sizeBlock).growX().row()
        col.add(head("张数")).growX().padTop(dp(18f)).row()
        col.add(countChips).growX().padTop(dp(10f)).row()
        col.add(refHeading).growX().padTop(dp(18f)).row()
        col.add(refRow).growX().padTop(dp(10f)).row()

        col.add(generate).growX().height(dp(48f)).padTop(dp(22f)).row()
        col.add(status).growX().padTop(dp(10f)).row()
        col.add(grid).growX().padTop(dp(8f)).row()

        add(ScrollPane(col).apply { setScrollingDisabledX(true); setOverscroll(false, true) }).grow()
        rebuild()
        loadFreeOption()
    }

    private fun head(title: String, hint: String? = null) = Table().apply {
        add(K.text(title, 15f, K.text, lh = 15f)).left()
        if (hint != null) add(K.text(hint, 13f, K.subtle, lh = 13f)).left().padLeft(dp(8f))
        add().growX()
    }

    private fun <T> chips(into: Table, opts: List<Pair<T, String>>, current: T, pick: (T) -> Unit) {
        into.clearChildren()
        opts.forEachIndexed { i, (v, label) ->
            into.add(chip(label, v == current) { pick(v); rebuild() })
                .growX().uniformX().padLeft(if (i > 0) dp(4f) else 0f).padRight(if (i < opts.lastIndex) dp(4f) else 0f)
        }
    }

    private fun rebuild() {
        routeChips.clearChildren()
        routeChips.add(chip("当前普通模型", !free) { free = false; rebuild() }).growX().row()
        routeChips.add(chip(if (loadingFree) "读取福利…" else "免费 gpt-image-2", free) {
            if (!service.freeReady) { project()?.notify("请先登录 NewAPI"); return@chip }
            if (freeModel == null) { error = "免费额度暂不可用，可刷新福利状态；已有请求仍可重试"; loadFreeOption(); rebuild(); return@chip }
            if (count != 1 || refs.isNotEmpty()) { error = "免费生图只支持 1 张，先清除参考图并将张数设为 1"; rebuild(); return@chip }
            free = true; error = null; rebuild()
        }).growX().padTop(dp(8f))
        chips(modeChips, listOf(CreationMode.GAME_SPRITE to "单位贴图", CreationMode.GENERAL to "普通图片"), mode) { mode = it }
        chips(sizeChips, listOf(32 to "32 px", 64 to "64 px", 128 to "128 px"), size) { size = it }
        chips(countChips, if (free) listOf(1 to "1 张") else listOf(1 to "1 张", 2 to "2 张", 4 to "4 张"), count) { count = it }
        // 只有单位贴图要选尺寸；普通图片时整块不占位
        sizeBlock.clearChildren()
        if (mode == CreationMode.GAME_SPRITE) {
            sizeBlock.add(head("贴图尺寸")).growX().padTop(dp(18f)).row()
            sizeBlock.add(sizeChips).growX().padTop(dp(10f))
        }

        refRow.clearChildren()
        refHeading.clearChildren()
        refHeading.add(head("参考图", if (free) null else "可选，改图时用")).growX()
        for (f in refs) {
            val t = Tap(K.input, 10f) { refs.remove(f); rebuild() }
            t.clipRadius = dp(10f)
            t.name = "移除参考图"
            t.add(ImageThumb(f)).grow()
            refRow.add(t).size(dp(64f)).padRight(dp(8f))
        }
        if (free) refRow.add(K.para("免费生图不支持参考图或改图", 13f, K.subtle, lh = 20f)).growX()
        else if (refs.size < 3) {
            val add = Tap(null, 10f) { pickRef() }.apply { name = "添加参考图"; border = K.inputBorder; add(K.svg(PI.plus, 20f, K.muted, 1.8f)) }
            refRow.add(add).size(dp(64f))
        }

        status.clearChildren()
        val m = model()?.takeIf { it.isNotBlank() }
        val line = when {
            error != null -> K.para(error!!, 14f, K.danger, lh = 20f)
            free -> K.para("免费 gpt-image-2 · 每次 1 张 1024×1024 PNG · 生成工具共享每日次数", 13f, K.subtle, lh = 20f)
            m == null -> K.para("还没选生图模型：到「设置 › 模型与渠道」里选一个", 14f, K.subtle, lh = 20f)
            m.startsWith(FreePicGenerator.FREE_PREFIX) -> K.para("当前模型是免费福利，请选择「免费 gpt-image-2」", 13f, K.subtle, lh = 20f)
            else -> K.para("模型 $m · 每张都会按线路计费", 13f, K.subtle, lh = 18f)
        }
        status.add(line).growX().left()
        val pending = runCatching { service.pendingFree() }.getOrNull()
        if (pending != null) {
            status.row()
            status.add(chip("重试原免费图片", false, 40f) { run(retry = true) }.apply { name = "重试原免费图片" }).left().padTop(dp(8f))
        }

        grid.clearChildren()
        results.forEachIndexed { i, img ->
            val f = img.pixel ?: img.file
            val cell = Table()
            val thumb = Table(K.fill(K.dl("2B2F27", "E6E8E0"), 12f)).apply { clipRadius = dp(12f); add(ImageThumb(f, tint = true)).grow() }
            cell.add(thumb).growX().height(dp(150f)).row()
            val save = Tap(K.surface2, 10f) { save(f) }.apply { name = "存到项目"; add(K.text("存到项目", 14f, K.text)) }
            cell.add(save).growX().height(dp(36f)).padTop(dp(8f))
            grid.add(cell).growX().uniformX().top().padRight(if (i % 2 == 0) dp(5f) else 0f).padLeft(if (i % 2 == 1) dp(5f) else 0f).padTop(dp(10f))
            if (i % 2 == 1) grid.row()
        }
        if (results.size % 2 == 1) grid.add().growX().uniformX()
        generate.busy = job?.isActive == true
    }

    private fun loadFreeOption() {
        if (loadingFree) return
        loadingFree = true
        scope.launch {
            val value = runCatching { service.freeOption() }.getOrNull()
            Core.app.post { freeModel = value; loadingFree = false; rebuild() }
        }
    }

    private fun pickRef() {
        val ctx = project() ?: return
        val imgs = ctx.images()
        if (imgs.isEmpty()) return ctx.notify("项目里还没有图片")
        val g = ListGroup()
        for (f in imgs.take(40)) {
            val row = Tap(K.surface, 0f) { ctx.closeSheet(); if (f !in refs) refs += f; rebuild() }
            row.name = f.name
            row.margin(dp(10f), dp(16f), dp(10f), dp(16f))
            row.add(Table(K.fill(K.input, 8f)).apply { clipRadius = dp(8f); add(ImageThumb(f)).grow() }).size(dp(44f)).padRight(dp(14f))
            row.add(K.text(f.name, 16f, K.text, lh = 16f).apply { setEllipsis(true) }).growX().minWidth(0f).left()
            g.row(row)
        }
        ctx.sheet(SheetPage("选择参考图", Table().top().apply { add(g).growX() }, height = 540f))
    }

    private fun run(retry: Boolean = false) {
        if (job?.isActive == true) { job?.cancel(); return }
        val text = prompt.text.trim()
        val ctx = project()
        if (!retry && text.isEmpty()) return ctx?.notify("先写一句提示词") ?: Unit
        if ((free || retry) && !service.freeReady) return ctx?.notify("请先登录 NewAPI") ?: Unit
        if (!retry && !free && model().isNullOrBlank()) return ctx?.notify("先在「设置 › 模型与渠道」里选生图模型") ?: Unit
        error = null
        Core.scene?.setKeyboardFocus(null)
        val req = PicRequest(text, mode, size, count, refs.toList(), free = free)
        job = scope.launch {
            val out = runCatching { if (retry) service.retryFree() else service.generate(req) }
            Core.app.post {
                out.onSuccess { results.addAll(0, it) }
                    .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = "生成失败：" + (it.message ?: it.javaClass.simpleName) }
                job = null
                rebuild()
            }
        }
        rebuild()
    }

    private fun save(f: File) {
        val ctx = project() ?: return
        val dir = ctx.projectDir() ?: return ctx.notify("先选一个项目")
        val dest = File(dir, "images/${f.name}").apply { parentFile.mkdirs() }
        runCatching { if (f.canonicalFile != dest.canonicalFile) f.copyTo(dest, overwrite = true) }
            .onSuccess { ctx.notify("已存到产物：images/${f.name}") }
            .onFailure { ctx.notify("保存失败：${it.message}") }
    }

    private fun askRust() {
        val ctx = project() ?: return
        if (free) {
            ctx.notify("免费图片请在本页生成，或先新建免费会话再交给${io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.assistant}")
            return
        }
        val text = prompt.text.trim()
        ctx.askRust(
            if (text.isEmpty()) "帮我画一张单位贴图"
            else if (mode == CreationMode.GAME_SPRITE) "按这个描述画一张 ${size}×$size 的单位贴图：$text" else "按这个描述画一张图：$text"
        )
    }

    /** 浅底「生成」按钮；生成中显示脉动的「生成中…（点按取消）」。 */
    private inner class GenerateButton : Tap(K.light, 12f, { run() }) {
        private val label = K.text("生成", 16f, K.onLight, 600).apply { setWrap(true); setAlignment(arc.util.Align.center) }
        private var t = 0f
        var busy = false
            set(v) { field = v; label.setText(if (v) "取消生成" else "生成"); name = if (v) "取消生成" else "生成" }

        init {
            name = "生成"
            add(label).growX().minWidth(0f)
        }

        override fun act(delta: Float) {
            super.act(delta)
            t += delta
            label.color.a = if (busy) 0.45f + 0.55f * pulse(t, 1.4f) else 1f
        }
    }
}
