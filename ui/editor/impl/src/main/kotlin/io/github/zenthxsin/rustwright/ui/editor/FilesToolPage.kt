package io.github.zenthxsin.rustwright.ui.editor

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import io.github.zenthxsin.rustwright.ui.editor.EditorChrome

import io.github.zenthxsin.rustwright.ui.kit.PageBar
import io.github.zenthxsin.rustwright.ui.kit.pageBar
import io.github.zenthxsin.rustwright.ui.kit.OwnHeader
import arc.Core
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import io.github.zenthxsin.rustwright.ui.kit.SheetPage
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.listRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件管理（「更多 → 文件管理」）：浏览当前项目目录。路径条可点回上级；
 * 文件夹在前，点文本文件进对应编辑器、点图片看大图，其它操作（重命名 / 删除 / 交给助手）在行尾「…」里。
 */
class FilesToolPage(private val project: () -> ProjectContext?, private val scope: CoroutineScope) : Table(), io.github.zenthxsin.rustwright.ui.designsystem.BackHandler, OwnHeader {


    override fun onBackRequested(leave: () -> Unit): Boolean {
        val current = dir ?: return false
        if (current == root) return false
        dir = current.parentFile
        refresh()
        return true
    }
    private var dir: File? = null
    private var root: File? = null
    private val crumbs = Table().left()
    private val list = Table().top()
    private var stamp = 0L

    private val bar = PageBar("文件管理") { project()?.projectName()?.let { n -> n + (dir?.let { d -> root?.let { r -> d.relativeToOrNull(r)?.path?.takeIf { it.isNotEmpty() }?.let { " / $it" } } } ?: "") } }
    override fun bindBack(back: () -> Unit) = bar.bindBack(back)

    init {
        top()
        pageBar(bar)
        bar.action(PI.fileUp, "导入文件") { importFile() }
        lateinit var plus: io.github.zenthxsin.rustwright.ui.kit.Tap
        plus = bar.action(PI.plus, "新建") { newMenu(plus) }
        val col = Table().top()
        col.margin(dp(4f), dp(20f), dp(24f), dp(20f))
        col.add(ScrollPane(crumbs).apply { setScrollingDisabledY(true); setOverscroll(false, false) }).growX().height(dp(30f)).row()
        col.add(list).growX().padTop(dp(14f)).row()
        add(ScrollPane(col).apply { setScrollingDisabledX(true); setOverscroll(false, true) }).grow()
        refresh()
    }

    override fun act(delta: Float) {
        super.act(delta)
        val r = project()?.projectDir()
        if (r != root) { root = r; dir = r; refresh(); return }
        // 文件夹内容变了（助手写了文件）就刷新
        val d = dir ?: return
        val s = d.lastModified() + (d.list()?.size ?: 0)
        if (s != stamp) refresh()
    }

    private fun refresh() {
        root = project()?.projectDir()
        if (dir == null || root == null || dir?.startsWith(root!!) != true) dir = root
        val d = dir
        stamp = (d?.lastModified() ?: 0L) + (d?.list()?.size ?: 0)
        buildCrumbs()
        list.clearChildren()
        if (d == null) {
            list.add(K.para("还没有选项目。先在「项目」里选一个，或新建一个普通项目。", 15f, K.subtle, lh = 23f)).growX().padTop(dp(30f))
            return
        }
        val entries = d.listFiles().orEmpty().filter { !it.name.startsWith(".") }
            .sortedWith(compareBy<File> {
                // 沿用图标的类型分类：文件夹、文本、贴图、地图、其他文件。
                when (kindIcon(it)) {
                    PI.folder -> 0; PI.code -> 1; PI.img -> 2; else -> 3
                }
            }.thenBy { it.lastModified() }.thenBy { it.name.lowercase() })
        if (entries.isEmpty()) {
            list.add(K.para("这个文件夹是空的", 15f, K.subtle, lh = 23f)).growX().padTop(dp(30f))
            return
        }
        val g = ListGroup()
        for (f in entries) g.row(row(f))
        list.add(g).growX()
    }

    private fun buildCrumbs() {
        crumbs.clearChildren()
        val r = root ?: return
        val chain = generateSequence(dir) { if (it == r) null else it.parentFile }.toList().reversed()
        chain.forEachIndexed { i, f ->
            if (i > 0) crumbs.add(K.svg(PI.chevronRight, 14f, K.subtle, 2f)).padLeft(dp(4f)).padRight(dp(4f))
            val last = i == chain.lastIndex
            val name = if (f == r) project()?.projectName() ?: r.name else f.name
            val t = Tap(null, 8f) { if (!last) { dir = f; refresh() } }
            t.name = name
            t.add(K.text(name, 15f, if (last) K.text else K.muted, if (last) 500 else 400, 15f))
            t.margin(dp(4f), dp(4f), dp(4f), dp(4f))
            crumbs.add(t)
        }
    }

    private fun kindIcon(f: File): String = when {
        f.isDirectory -> PI.folder
        f.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") -> PI.img
        TextFiles.isText(f) -> PI.code
        else -> PI.file
    }

    private fun meta(f: File): String =
        if (f.isDirectory) "${f.list()?.count { !it.startsWith(".") } ?: 0} 项"
        else listOf(size(f.length()), age(f.lastModified())).joinToString(" · ")

    private fun row(f: File): Tap {
        val more = Tap(null, 18f) { actions(f) }.apply { name = "更多操作"; add(K.svg(PI.dots, 20f, K.subtle, 2f)) }
        more.minW = dp(36f); more.minH = dp(36f)
        val r = listRow(f.name, meta(f), kindIcon(f), if (f.isDirectory) K.c("E9A23B") else K.muted, minHeight = 62f, iconStroke = 1.6f) { open(f) }
        if (f.isDirectory) r.add(K.svg(PI.chevronRight, 18f, K.subtle, 2f)).padLeft(dp(6f))
        else r.add(more).padLeft(dp(6f)).padRight(dp(-8f))
        return r
    }

    private fun open(f: File) {
        val ctx = project() ?: return
        when {
            f.isDirectory -> { dir = f; refresh() }
            TextFiles.isText(f) -> ctx.openTool("file", f)
            kindIcon(f) == PI.img -> preview(f)
            else -> actions(f)
        }
    }

    private fun preview(f: File) {
        val ctx = project() ?: return
        val body = Table().top()
        body.margin(0f, dp(20f), dp(20f), dp(20f))
        body.add(Table(K.fill(K.dl("2B2F27", "E6E8E0"), 12f)).apply { clipRadius = dp(12f); add(io.github.zenthxsin.rustwright.ui.kit.ZoomImage(f)).grow() }).growX().height(dp(280f)).row()
        body.add(K.text(meta(f), 13f, K.subtle)).left().padTop(dp(10f)).row()
        val b = Table()
        b.add(Tap(K.light, 12f) { ctx.closeSheet(); ctx.openTool("file", f) }.apply { name = "查看图片"; add(K.text("查看图片", 16f, K.onLight, 600)) }).growX().uniformX().height(dp(46f)).padRight(dp(5f))
        b.add(Tap(null, 12f) { ctx.closeSheet(); actions(f) }.apply { name = "更多"; border = K.inputBorder; add(K.text("更多操作", 16f, K.text)) }).growX().uniformX().height(dp(46f)).padLeft(dp(5f))
        body.add(b).growX().padTop(dp(14f))
        ctx.sheet(SheetPage(f.name, body, height = 520f))
    }

    private fun actions(f: File) {
        val ctx = project() ?: return
        val g = ListGroup()
        val rel = root?.let { f.relativeToOrNull(it)?.path } ?: f.name
        if (TextFiles.isText(f)) g.row(listRow("打开编辑器", null, PI.code, minHeight = 56f) { ctx.closeSheet(); ctx.openTool("file", f) })
        if (kindIcon(f) == PI.img) g.row(listRow("查看图片", null, PI.pixel, minHeight = 56f) { ctx.closeSheet(); ctx.openTool("file", f) })
        if (f.extension.lowercase() in setOf("zip"))
            g.row(listRow("解压", "解压到同名文件夹", PI.folder, minHeight = 56f) { ctx.closeSheet(); extract(f) })
        g.row(listRow("交给${AppBrand.identity.assistant}", null, PI.chat, minHeight = 56f) { ctx.closeSheet(); ctx.askRust("看看 $rel，") })
        g.row(listRow(if (f.isDirectory) "导出为 zip" else "导出", "保存到手机", PI.up, minHeight = 56f) { ctx.closeSheet(); export(f) })
        g.row(listRow("重命名", null, PI.edit, minHeight = 56f) { ctx.closeSheet(); rename(f) })
        g.row(listRow("删除", null, PI.trash, K.danger, titleColor = K.danger, minHeight = 56f) { ctx.closeSheet(); delete(f) })
        ctx.sheet(SheetPage(f.name, Table().top().apply { add(g).growX().pad(0f, dp(10f), dp(20f), dp(10f)) }, height = 500f))
    }

    private fun extract(file: File) {
        val ctx = project() ?: return
        val projectRoot = root ?: return
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                    io.github.zenthxsin.rustwright.core.runtime.archive.ZipArchive.extract(file, projectRoot) {
                        if (job?.isActive == false) throw kotlinx.coroutines.CancellationException()
                    }
                }
                Core.app.post { ctx.notify("已解压到 ${result.name}"); refresh() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Core.app.post { ctx.notify("解压失败：${e.message}") } }
        }
    }

    /** 导出到手机：文件直接存；文件夹先打成 zip（保留文件夹名这一层）。 */
    private fun export(f: File) {
        val ctx = project() ?: return
        scope.launch {
            val r = runCatching {
                val out = if (f.isDirectory) withContext(Dispatchers.IO) {
                    File(ctx.cacheDir(), "export/${f.name}.zip").apply {
                        parentFile.mkdirs(); delete()
                        java.util.zip.ZipOutputStream(outputStream().buffered()).use { zip ->
                            f.walkTopDown().filter { it.isFile }.forEach { file ->
                                zip.putNextEntry(java.util.zip.ZipEntry(f.name + "/" + file.relativeTo(f).invariantSeparatorsPath))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                } else f
                val mime = when (out.extension.lowercase()) {
                    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "zip" -> "application/zip"
                    "ini", "txt", "md", "py", "js", "json", "xml" -> "text/plain"
                    else -> "application/octet-stream"
                }
                out to ctx.exportFile(out, out.name, mime)
            }
            Core.app.post {
                r.fold({ (out, ok) -> if (ok) ctx.notify("已导出 ${out.name}") }, { ctx.notify("导出失败：${it.message}") })
            }
        }
    }

    private fun rename(f: File) {
        scope.launch {
            val name = io.github.zenthxsin.rustwright.ui.widgets.Dialogs.input("重命名", hint = f.name, initial = f.name)?.trim()?.takeIf { it.isNotEmpty() && it != f.name } ?: return@launch
            val ok = withContext(Dispatchers.IO) { !name.contains('/') && f.renameTo(File(f.parentFile, name)) }
            Core.app.post { if (!ok) project()?.notify("重命名失败"); refresh() }
        }
    }

    private fun delete(f: File) {
        scope.launch {
            if (!io.github.zenthxsin.rustwright.ui.widgets.Dialogs.confirm("删除", "确定删除「${f.name}」？删除后无法恢复", ok = "删除")) return@launch
            withContext(Dispatchers.IO) { f.deleteRecursively() }
            Core.app.post { project()?.notify("已删除 ${f.name}"); refresh() }
        }
    }

    /** 右上角「+」：和对话右上角同款的下拉菜单——新建文件夹 / 文件，导入工程 / 模组（成为新项目）。 */
    private fun newMenu(anchor: arc.scene.Element) {
        fun inDir(block: suspend (File) -> Unit) {
            val d = dir ?: return project()?.notify("先选一个项目") ?: Unit
            scope.launch { block(d) }
        }
        EditorChrome.menu(anchor, listOf(
            EditorChrome.Entry("新建文件夹", PI.folder) { inDir { newFolder(it) } },
            EditorChrome.Entry("新建文件", PI.file) { inDir { newFile(it) } },
            EditorChrome.Entry("导入工程", PI.archive) { importProject() },
        ))
    }

    private fun importProject() {
        val ctx = project() ?: return
        scope.launch {
            val d = runCatching { ctx.importProject() }.getOrNull() ?: return@launch
            Core.app.post { dir = d; refresh() }
        }
    }

    private suspend fun newFolder(d: File) {
        val name = io.github.zenthxsin.rustwright.ui.widgets.Dialogs.input("新建文件夹", hint = "例如 units")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val bad = when {
            '/' in name || '\\' in name -> "名字里不能有 /"
            File(d, name).exists() -> "「$name」已经存在"
            else -> null
        }
        if (bad != null) { Core.app.post { project()?.notify(bad) }; return }
        withContext(Dispatchers.IO) { File(d, name).mkdirs() }
        Core.app.post { refresh() }
    }

    /** 文件名要带扩展名（readme.txt、readme.md），建好直接按类型打开。 */
    private suspend fun newFile(d: File) {
        val name = io.github.zenthxsin.rustwright.ui.widgets.Dialogs.input("新建文件", hint = "带扩展名，例如 readme.txt")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val ext = name.substringAfterLast('.', "")
        val bad = when {
            '/' in name || '\\' in name -> "名字里不能有 /"
            ext.isEmpty() || name.startsWith('.') && name.count { it == '.' } == 1 -> "文件名要带扩展名，例如 readme.txt"
            File(d, name).exists() -> "「$name」已经存在"
            else -> null
        }
        if (bad != null) { Core.app.post { project()?.notify(bad) }; return }
        val f = File(d, name)
        val ok = withContext(Dispatchers.IO) { runCatching { f.createNewFile() }.getOrDefault(false) }
        Core.app.post {
            refresh()
            if (!ok) project()?.notify("新建失败") else project()?.openTool("file", f)
        }
    }

    private fun importFile() {
        val ctx = project() ?: return
        val d = dir ?: return ctx.notify("先选一个项目")
        scope.launch {
            val sources = try { ctx.importFiles() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Core.app.post { ctx.notify("选择文件失败：${e.message}") }; return@launch }
            if (sources.isEmpty()) return@launch
            var imported = 0
            val failures = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                for (src in sources) {
                    try {
                        io.github.zenthxsin.rustwright.core.runtime.ImportedFiles.copy(src, d) { if (job?.isActive == false) throw kotlinx.coroutines.CancellationException() }
                        imported++
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { failures += src.name }
                }
            }
            Core.app.post {
                ctx.notify("已导入 $imported 个文件" + if (failures.isEmpty()) "" else "，${failures.size} 个失败")
                refresh()
            }
        }
    }

    private companion object {
        fun size(b: Long): String = when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> "%.1f KB".format(b / 1024f)
            else -> "%.1f MB".format(b / 1048576f)
        }

        fun age(t: Long): String {
            val d = (System.currentTimeMillis() - t) / 1000
            return when {
                d < 60 -> "刚刚"
                d < 3600 -> "${d / 60} 分钟前"
                d < 86400 -> "${d / 3600} 小时前"
                d < 86400 * 30 -> "${d / 86400} 天前"
                else -> java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(t))
            }
        }
    }
}
