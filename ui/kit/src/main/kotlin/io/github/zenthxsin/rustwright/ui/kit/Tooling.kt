package io.github.zenthxsin.rustwright.ui.kit

import arc.graphics.Color
import arc.graphics.g2d.Shapes
import arc.input.KeyCode
import arc.scene.Element
import arc.scene.event.InputEvent
import arc.scene.event.InputListener
import arc.scene.event.Touchable
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import java.io.File

/**
 * 工具页（「更多」里的像素化、生图、INI 编辑器、文件管理、地图画布）拿当前项目和外壳能力的入口，
 * 由融合版外壳提供（ctx.services）。
 */
interface ProjectContext {
    /** 当前项目在本机的目录；没选项目时为 null。 */
    fun projectDir(): File?
    fun projectName(): String
    /** 当前项目里的图片，最新的在前。 */
    fun images(): List<File> = projectDir()?.walkTopDown()?.onEnter { !it.name.startsWith(".") }
        ?.filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") }
        ?.sortedByDescending { it.lastModified() }?.toList().orEmpty()
    /** 回到对话并把话填进输入框（「交给助手」）。 */
    fun askRust(text: String)
    /** 顶部通知。 */
    fun notify(text: String)
    /** 顶部通知，点一下才执行 [open]（助手想给用户看什么时用，不直接切页面打断用户）。 */
    fun suggest(text: String, open: () -> Unit) = notify(text)
    /** 打开底部弹层。 */
    fun sheet(page: SheetPage)
    fun back() { closeSheet() }
    fun closeSheet()
    /** 打开「更多」里的另一个工具页；带 [file] 时工具页用 [ToolRequests.take] 取走（文件管理 → INI 编辑器）。 */
    fun openTool(id: String, file: File? = null) {}
    /** 以带返回的工具页打开任意界面（设置项、侧边栏入口点开的详情页）。 */
    fun openPage(title: String, content: arc.scene.Element, desc: String = "") {}
    /** 通过指定的项目类型导入工程压缩包，并切到新项目。成功返回新项目目录。 */
    suspend fun importProject(type: String? = null): File? = null
    /** 从系统选一个文件导入（返回本地副本）；不支持时为 null。 */
    suspend fun importFile(): File? = null
    suspend fun importFiles(): List<File> = listOfNotNull(importFile())
    /** 用系统「保存到…」导出一个文件；用户取消返回 false。 */
    suspend fun exportFile(file: File, name: String, mime: String): Boolean = false
    /** 系统分享。 */
    fun shareFile(file: File, mime: String) {}
    /** 本机缓存目录（打包产物等临时文件）。 */
    fun cacheDir(): File = File(System.getProperty("java.io.tmpdir"))
    /** 所有没归档的项目（工具页里切项目用）。 */
    fun projects(): List<ProjectRef> = emptyList()
    /** 切到另一个项目（和「项目」页点选一样）。 */
    fun switchProject(id: String) {}
    /** 新建项目（弹窗起名）并切过去；取消返回 false。 */
    suspend fun newProject(): Boolean = false
}

/** 项目列表里的一项：[dir] 是本机目录（拿不到时为 null）。 */
data class ProjectRef(val id: String, val name: String, val dir: File?, val current: Boolean)

/** 带文件打开工具页的请求，工具页在 act 里取走。 */
object ToolRequests {
    private val pending = HashMap<String, File>()
    fun offer(id: String, file: File) { pending[id] = file }
    fun take(id: String): File? = pending.remove(id)
}

/** 原型 chip：选中为浅底深字，未选中为透明底 + #3A3A38 描边（高 38、圆角 10、14px）。 */
fun chip(label: String, on: Boolean, height: Float = 38f, onClick: () -> Unit): Tap =
    Tap(if (on) K.text else null, 10f, onClick).apply {
        border = if (on) K.text else K.inputBorder
        name = label
        add(K.text(label, 14f, if (on) K.onLight else K.text, lh = 14f))
        // 不撑满时（单独一个 chip）左右留 14 的内边距
        margin(0f, dp(14f), 0f, dp(14f))
        minH = dp(height)
    }

/** 浏览器 range 控件（accent-color #C2603A）：4dp 轨道，已选部分锈橙，16dp 圆形拇指。 */
class Slider(var min: Float, var max: Float, var value: Float, private val step: Float = 1f, private val onChange: (Float) -> Unit) : Element() {
    init {
        touchable = Touchable.enabled
        addListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: KeyCode): Boolean { set(x); return true }
            override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) = set(x)
        })
    }

    private fun set(lx: Float) {
        val r = dp(8f)
        val t = ((lx - r) / (width - 2 * r)).coerceIn(0f, 1f)
        val v = (min + (max - min) * t).let { Math.round(it / step) * step }.coerceIn(min, max)
        if (v != value) { value = v; onChange(v) }
    }

    override fun getPrefHeight() = dp(24f)

    override fun draw() {
        val a = parentAlpha * color.a
        val r = dp(8f)
        val cy = y + height / 2f
        val t = ((value - min) / (max - min)).coerceIn(0f, 1f)
        val tx = x + r + (width - 2 * r) * t
        Shapes.rect(x + r, cy - dp(2f), width - 2 * r, dp(4f), dp(2f), Color(K.inputBorder).a(a))
        Shapes.rect(x + r, cy - dp(2f), tx - x - r, dp(4f), dp(2f), Color(K.accent).a(a))
        Shapes.circle(tx, cy, r, Color(K.accent).a(a))
    }
}
