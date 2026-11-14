package io.github.zenthxsin.rustwright.pic.impl

import arc.graphics.Pixmap
import arc.graphics.Texture
import arc.graphics.g2d.TextureRegion
import arc.scene.ui.Image
import arc.scene.ui.Label
import arc.scene.ui.ScrollPane
import arc.scene.ui.TextButton
import arc.scene.ui.TextField
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.util.Scaling
import io.github.zenthxsin.rustwright.core.runtime.Platform
import io.github.zenthxsin.rustwright.pic.api.CreationMode
import io.github.zenthxsin.rustwright.pic.api.PicImage
import io.github.zenthxsin.rustwright.pic.api.PicRequest
import io.github.zenthxsin.rustwright.pic.api.PicService
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import io.github.zenthxsin.rustwright.ui.designsystem.BackHandler
import io.github.zenthxsin.rustwright.ui.designsystem.Icons
import io.github.zenthxsin.rustwright.ui.designsystem.Rw
import io.github.zenthxsin.rustwright.ui.designsystem.RwButton
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import io.github.zenthxsin.rustwright.ui.widgets.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 生图页：描述 + 模式（贴图 / 通用）+ 贴图尺寸 + 参考图 + 生成，下方可筛选的图库网格。
 * 点图片可像素化、导出、删除、作参考图。生成前同样弹确认（与助手的 generate_image 一致，会花钱）。
 */
class PicScreen(
    private val service: PicService,
    private val platform: Platform,
    private val scope: CoroutineScope,
) : Table(), BackHandler {

    private var mode = CreationMode.GAME_SPRITE
    private var size = 64
    private var free = service.selectedFree
    /** 图库筛选：0 全部、1 贴图、2 通用。 */
    private var filter = 0
    private val prompt = arc.scene.ui.TextArea("").apply { setMessageText("描述你要的单位 / 建筑 / 特效…") }
    private val grid = Table().top().left()
    private val textures = HashMap<File, Texture>()
    private var busy: Job? = null
    private val watcher: Job
    /** 参考图（改图 / 变体）；为空则文生图。 */
    private val references = ArrayList<File>()
    private var editor: PixelEditorPanel? = null

    private val form = Rw.card().apply {
        top().left()
        add(Rw.caption("描述")).left().padBottom(dp(5f)).row()
        add(Table(RwTheme.card(RwTheme.Colors.bg, radiusDp = 10)).apply {
            margin(dp(3f), dp(8f), dp(3f), dp(8f))
            prompt.style = RwTheme.Styles.plainField
            prompt.setPrefRows(3f)
            add(prompt).growX()
        }).growX().row()
        add(Table().left().apply {
            add(RwButton("", style = RwTheme.Styles.chip).apply {
                update { setText(if (free) "免费 gpt-image-2" else "普通线路") }
                clicked {
                    if (free) { free = false; return@clicked }
                    scope.launch {
                        val available = runCatching { service.freeOption() }.getOrNull()
                        withContext(ArcDispatcher) {
                            if (available != null && references.isEmpty()) free = true
                            else Toasts.show(if (references.isNotEmpty()) "先清除参考图，免费生图不支持改图" else "请登录 NewAPI 并查看福利额度")
                        }
                    }
                }
            }).height(dp(32f)).left().row()
        }).growX().padTop(dp(9f)).row()
        add(Table().left().apply {
            add(RwButton("", Icons.expandMore, RwTheme.Styles.chip, trailing = true).apply {
                margin(0f, dp(6f), 0f, dp(9f))
                update { setText(if (mode == CreationMode.GAME_SPRITE) "贴图" else "通用") }
                clicked { mode = if (mode == CreationMode.GAME_SPRITE) CreationMode.GENERAL else CreationMode.GAME_SPRITE }
            }).height(dp(28f)).padRight(dp(6f))
            add(RwButton("", Icons.expandMore, RwTheme.Styles.chip, trailing = true).apply {
                margin(0f, dp(6f), 0f, dp(9f))
                update { setText("$size px"); isDisabled = mode != CreationMode.GAME_SPRITE }
                clicked { size = when (size) { 32 -> 48; 48 -> 64; 64 -> 96; 96 -> 128; else -> 32 } }
            }).height(dp(28f)).padRight(dp(6f))
            add(RwButton("参考图", Icons.add, RwTheme.Styles.chip).apply {
                margin(0f, dp(9f), 0f, dp(8f))
                update { setText(if (references.isEmpty()) "参考图" else "参考图 ${references.size} · 清除"); isChecked = references.isNotEmpty(); isDisabled = free }
                clicked {
                    if (references.isEmpty()) Toasts.show("在图库里点一张图，选「作为参考图」") else { references.clear(); rebuild(service.images.value) }
                }
            }).height(dp(28f))
        }).left().padTop(dp(9f)).row()
        add(Rw.primary("生成", Icons.sparkle) { generate() }).growX().height(dp(38f)).padTop(dp(9f))
        row()
        add(RwButton("重试原免费图片", style = RwTheme.Styles.secondary).apply {
            update { isDisabled = busy?.isActive == true || !service.freeReady || runCatching { service.pendingFree() == null }.getOrDefault(true) }
            clicked { generate(retry = true) }
        }).growX().height(dp(36f)).padTop(dp(6f))
    }

    private val galleryPane = ScrollPane(Table().top().left().apply {
        margin(dp(12f))
        add(form).growX().row()
        add(Table().left().apply {
            add(Label("图库"))
            add(Rw.caption("").apply { update { setText(service.images.value.size.toString()) } }).padLeft(dp(6f))
            add().growX()
            add(Rw.segmented(listOf("全部", "贴图", "通用"), { filter }) { filter = it; rebuild(service.images.value) })
        }).growX().padTop(dp(12f)).padBottom(dp(8f)).row()
        add(grid).growX()
    }).apply { setScrollingDisabled(true, false); setFadeScrollBars(true) }

    init {
        showGallery()
        watcher = scope.launch(ArcDispatcher) { service.images.collect { rebuild(it) } }
    }

    private fun showGallery() {
        editor = null
        clearChildren()
        top()
        add(Rw.topBar(
            "生图",
            titleElement = Table().left().apply {
                add(Rw.title("生图")).left().row()
                add(Rw.caption("").apply { update { setText(if (service.ready) "已选择生图模型" else "还没选择生图模型（在${io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.assistant}的设置里选）") } }).left()
            },
        ).apply { background(RwTheme.solid(RwTheme.Colors.surface)) }).growX().row()
        add(Rw.divider()).growX().height(dp(1f)).row()
        add(galleryPane).grow()
    }

    /** 用像素编辑器打开 [file]（图库图片或模组里的 PNG），关闭后回到图库。 */
    fun openEditor(file: File) {
        val panel = runCatching { PixelEditorPanel(file, scope) { saved ->
            if (saved) { textures.remove(file)?.dispose(); rebuild(service.images.value) }
            showGallery()
        } }.getOrElse { return Toasts.show("无法打开：${it.message}") }
        clearChildren()
        editor = panel
        add(panel).grow()
    }

    override fun onBackRequested(leave: () -> Unit): Boolean =
        editor?.onBackRequested {} ?: false

    private fun rebuild(images: List<PicImage>) {
        grid.clearChildren()
        val shown = when (filter) {
            1 -> images.filter { it.pixel != null }
            2 -> images.filter { it.pixel == null }
            else -> images
        }
        val generating = busy?.isActive == true
        if (shown.isEmpty() && !generating) {
            grid.add(Rw.empty(Icons.gallery, if (images.isEmpty()) "图库是空的。写好描述点「生成」，或在${io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.assistant}里说「帮我画一个图片」。" else "没有符合筛选的图片"))
                .growX().padTop(dp(20f))
            return
        }
        val columns = 3
        val gap = dp(6f)
        val cell = (((galleryPane.width.takeIf { it > 0f } ?: dp(360f)) - dp(28f) - gap * (columns - 1)) / columns).coerceAtLeast(dp(60f))
        var index = 0
        fun place(tile: arc.scene.Element) {
            grid.add(tile).size(cell).padRight(if (index % columns == columns - 1) 0f else gap).padBottom(gap)
            if (++index % columns == 0) grid.row()
        }
        if (generating) place(Table(RwTheme.round(12, RwTheme.Colors.surface2)).apply {
            add(Label(Icons.sparkle.toString(), Label.LabelStyle(RwTheme.icons, RwTheme.Colors.accentText))).row()
            add(Rw.caption("生成中")).padTop(dp(3f))
        })
        shown.forEachIndexed { i, img ->
            val isRef = img.file in references
            place(Table(if (isRef) RwTheme.card(RwTheme.Colors.surface2, RwTheme.Colors.accent, 12) else RwTheme.round(12, RwTheme.Colors.surface2)).apply {
                name = "gallery-$i"
                margin(dp(5f))
                add(Image(region(img.pixel ?: img.file)).apply { setScaling(Scaling.fit) }).grow()
                touchable = arc.scene.event.Touchable.enabled
                clicked { open(img) }
            })
        }
    }

    private fun region(file: File): TextureRegion {
        val tex = textures.getOrPut(file) {
            val pix = Pixmap(file.readBytes())
            Texture(pix).apply { setFilter(Texture.TextureFilter.nearest) }.also { pix.dispose() }
        }
        return TextureRegion(tex)
    }

    private fun generate(retry: Boolean = false) {
        val text = prompt.text.trim()
        if (!retry && text.isEmpty()) return Toasts.show("先写提示词")
        if ((free || retry) && !service.freeReady) return Toasts.show("请先登录 NewAPI")
        if (!retry && !free && !service.ready) return Toasts.show("请先在${io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.assistant}设置里选择生图模型")
        if (busy?.isActive == true) return
        val request = PicRequest(text, mode, size, references = references.toList(), free = free)
        busy = scope.launch {
            val pending = if (free || retry) service.pendingFree() else null
            val refNote = if (request.references.isNotEmpty()) "\n\n参考图 ${request.references.size} 张（改图）" else ""
            val message = when {
                pending != null -> "恢复上次免费图片，使用原参数及请求编号，不重复扣次。"
                free -> "免费生成 1 张 1024×1024 PNG，生成工具共享每日次数：\n\n$text"
                else -> "将调用生图服务（会产生费用）：\n\n$text$refNote"
            }
            if (!Dialogs.confirm(if (pending != null) "重试原请求" else "确认生成", message, ok = if (pending != null) "重试" else "生成")) return@launch
            withContext(ArcDispatcher) { rebuild(service.images.value); Toasts.show("生成中，通常需要 30 秒到几分钟…") }
            val r = runCatching { if (pending != null) service.retryFree() else service.generate(request) }
            r.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            withContext(ArcDispatcher) {
                r.fold({ Toasts.show("生成了 ${it.size} 张") }, { Toasts.show("生成失败：${it.message}") })
            }
        }
        busy?.invokeOnCompletion { scope.launch(ArcDispatcher) { rebuild(service.images.value) } }
    }

    private fun open(img: PicImage) {
        scope.launch {
            val actions = listOf("关闭", "删除", "导出原图") + (if (img.pixel != null) listOf("导出贴图") else emptyList()) + "像素化" + "像素编辑" + "作为参考图"
            val detail = img.prompt.take(300) + (img.pixel?.let { "\n\n贴图：${it.name}" } ?: "")
            when (actions.getOrNull(Dialogs.choose("图片", detail, actions, highlight = -1))) {
                "删除" -> if (Dialogs.confirm("删除图片", "删除后无法恢复")) withContext(ArcDispatcher) { service.delete(img) }
                "导出原图" -> platform.exportFile(img.file, img.file.name, "image/png")
                "导出贴图" -> img.pixel?.let { platform.exportFile(it, it.name, "image/png") }
                "像素编辑" -> withContext(ArcDispatcher) { openEditor(img.pixel ?: img.file) }
                "作为参考图" -> withContext(ArcDispatcher) {
                    if (free) { Toasts.show("免费生图不支持参考图；可自行切换普通线路后再选"); return@withContext }
                    if (img.file !in references) references += img.file
                    if (references.size > 4) references.removeAt(0)
                    rebuild(service.images.value)
                    Toasts.show("下次生成会参考这张图（最多 4 张）")
                }
                "像素化" -> {
                    val s = Dialogs.input("贴图尺寸（8-512）", initial = size.toString())?.toIntOrNull() ?: return@launch
                    val r = runCatching { service.pixelate(img, s.coerceIn(8, 512)) }
                    withContext(ArcDispatcher) {
                        textures.remove(img.pixel)?.dispose()
                        r.fold({ Toasts.show("已生成 ${s}px 贴图") }, { Toasts.show("像素化失败：${it.message}") })
                    }
                }
            }
        }
    }

}
