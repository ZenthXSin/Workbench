package io.github.zenthxsin.rustwright.ui.codex

import arc.graphics.Color
import arc.scene.event.Touchable
import arc.scene.ui.Label
import arc.scene.ui.ScrollPane
import arc.scene.ui.TextField
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.codex.CodexConversation
import io.github.zenthxsin.rustwright.core.codex.CodexFileChange
import io.github.zenthxsin.rustwright.core.codex.CodexItem
import io.github.zenthxsin.rustwright.core.codex.CodexProject
import io.github.zenthxsin.rustwright.ui.designsystem.Lucide
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp

/**
 * 审阅面板（workspace-inspector.tsx，手机上替换会话区域）：
 * 背景 #fafafa；顶部 44 高白色页签「变更 / 文件 / 日志」（选中项深色 + 下划线）与关闭；
 * 变更：已变更文件统计 + 每个文件一张带行号的差异卡片；文件：项目文件浏览；日志：命令历史卡片。
 */
internal class CodexReviewPanel(
    private val conversation: CodexConversation?,
    private val project: CodexProject?,
    private val host: CodexHost,
    private var tab: Int,
    private val onTab: (Int) -> Unit,
    private val onClose: () -> Unit,
    private val onFiles: (dir: String, action: String) -> Unit,
    private val onExport: (name: String, content: String) -> Unit,
) : Table() {

    private val panelBg get() = if (RwTheme.dark) Color.valueOf("1b1b1b") else Color.valueOf("fafafa")
    private val items: List<CodexItem> get() = conversation?.turns?.flatMap { it.items }.orEmpty()
    private val viewed = HashSet<String>()
    private val collapsed = HashSet<String>()
    private var wrap = false
    private var dir = ""
    private val filter = TextField("", TextField.TextFieldStyle().apply {
        font = RwTheme.text(16f, 400, 24f)
        fontColor = Css.text
        messageFontColor = Css.muted
        cursor = RwTheme.solid(Css.text).also { it.minWidth = dp(1.5f) }
        selection = RwTheme.solid(RwTheme.Colors.info.cpy().a(0.3f))
    }).apply { setMessageText("筛选文件") }

    init {
        filter.changed { rebuild() }
        rebuild()
    }

    fun rebuild() {
        clearChildren()
        top()
        background(RwTheme.solid(panelBg))
        add(tabs()).growX().height(dp(44f)).row()
        add(Css.rule()).growX().height(dp(1f)).row()
        add(when (tab) { 0 -> changes(); 1 -> files(); else -> logs() }).grow()
    }

    /** 页签：padding 2 10 0，14px，选中黑色 + 2px 下划线，未选 #666；右侧 40px 关闭。 */
    private fun tabs() = Table(RwTheme.solid(Css.bg)).left().apply {
        margin(0f, dp(8f), 0f, dp(8f))
        listOf("变更", "文件", "日志").forEachIndexed { i, label ->
            add(Table().apply {
                name = label
                touchable = Touchable.enabled
                add(Css.label(label, 14f, if (i == tab) Css.text else Css.muted, lineHeight = 20f)).padTop(dp(2f)).expandY().row()
                add(arc.scene.ui.Image(RwTheme.solid(if (i == tab) Css.text else Color.clear))).growX().height(dp(2f))
                clicked { if (tab != i) { tab = i; onTab(i); rebuild() } }
            }).width(dp(48f)).growY()
        }
        add().growX()
        add(Css.iconButton(Lucide.x, 16f, Css.muted, 10, "关闭") { onClose() }).size(dp(40f))
    }

    private fun count(n: Int) = Table(RwTheme.round(10, RwTheme.Colors.userBubble)).apply {
        margin(dp(2f), dp(6f), dp(2f), dp(6f))
        add(Css.label(n.toString(), 12f, Css.muted, lineHeight = 16f))
    }

    private fun scroll(content: Table) = ScrollPane(content).apply { setScrollingDisabled(true, false); setFadeScrollBars(true); setOverscroll(false, false) }

    // region 变更

    private fun changes(): Table {
        val changes = items.flatMap { it.changes }.groupBy { it.path }.map { (_, v) -> v.last() }
        val added = changes.sumOf { it.added }
        val removed = changes.sumOf { it.removed }
        return Table().top().apply {
            add(Table().left().apply {
                margin(dp(12f), dp(16f), dp(12f), dp(4f))
                add(Table().left().apply {
                    add(Table().left().apply {
                        add(Css.label("已变更文件", 14f, Css.text, 500, 20f)).padRight(dp(8f))
                        add(count(changes.size))
                    }).left().row()
                    add(Css.label("相对于项目初始状态的变更", 12f, Css.muted, lineHeight = 20f)).left().padTop(dp(4f))
                }).growX().left()
                add(Css.label("+$added", 12f, Css.green, lineHeight = 16f)).padRight(dp(8f))
                add(Css.label("-$removed", 12f, RwTheme.Colors.diffRemoved, lineHeight = 16f))
                add(Css.iconButton(Lucide.refreshCw, 16f, Css.muted, 999, "刷新") { rebuild() }).size(dp(44f)).padLeft(dp(8f))
                add(Css.iconButton(Lucide.download, 16f, Css.muted, 999, "下载补丁") { downloadPatch(changes) }).size(dp(44f)).padLeft(dp(8f))
            }).growX().row()
            add(Css.rule()).growX().height(dp(1f)).row()
            add(Table(RwTheme.solid(Css.bg)).left().apply {
                margin(dp(8f), dp(16f), dp(8f), dp(16f))
                add(Css.ghost(10) {}.apply {
                    margin(0f, dp(8f), 0f, dp(8f))
                    add(Css.icon(Lucide.listFilter, 14f)).padRight(dp(8f))
                    add(Css.label("文件", 12f, Css.text, lineHeight = 16f))
                }).height(dp(32f))
                add(Css.label("已查看 ${viewed.count { v -> changes.any { it.path == v } }} / ${changes.size} 个文件", 12f, Css.muted, lineHeight = 16f)).growX().left().padLeft(dp(8f))
                add(Css.iconButton(Lucide.textWrap, 16f, if (wrap) Css.text else Css.muted, 10, "自动换行") { wrap = !wrap; rebuild() }).size(dp(32f))
            }).growX().row()
            add(Css.rule()).growX().height(dp(1f)).row()
            val list = Table().top().left()
            list.margin(dp(16f))
            if (changes.isEmpty()) list.add(Css.para("这个对话还没有修改文件。", 14f, Css.muted)).growX().row()
            changes.forEach { list.add(changeCard(it)).growX().padBottom(dp(12f)).row() }
            add(scroll(list)).grow()
        }
    }

    /** 差异卡片：圆角 16，页眉灰底（展开箭头 / 文件图标 / 文件名 13px 500 + 目录 12px / +N −N / 已查看 / 打开）。 */
    private fun changeCard(ch: CodexFileChange) = Table(RwTheme.card(Css.bg, Css.border, 16)).top().left().apply {
        val open = ch.path !in collapsed
        val fileName = ch.path.substringAfterLast('/')
        val parent = ch.path.substringBeforeLast('/', "").substringAfterLast("/")
        add(Table(RwTheme.solid(if (RwTheme.dark) Color(1f, 1f, 1f, 0.04f) else Color.valueOf("f6f6f6"))).left().apply {
            margin(0f, 0f, 0f, 0f)
            add(Css.iconButton(if (open) Lucide.chevronDown else Lucide.chevronRight, 14f, Css.text, 999, "展开") {
                if (!collapsed.add(ch.path)) collapsed.remove(ch.path); rebuild()
            }).size(dp(36f)).padLeft(dp(4f))
            add(Css.icon(Lucide.fileCode, 16f)).padRight(dp(10f))
            add(Table().left().apply {
                add(Css.label(fileName, 13f, Css.text, 500, 19.5f)).left().row()
                if (parent.isNotEmpty()) add(Css.label(parent, 12f, Css.muted, lineHeight = 16f)).left()
            }).growX().minWidth(0f)
            add(Css.label("+${ch.added}", 12f, Css.green, lineHeight = 16f)).padRight(dp(8f))
            add(Css.label("-${ch.removed}", 12f, RwTheme.Colors.diffRemoved, lineHeight = 16f)).padRight(dp(12f))
            val box = Css.icon(if (ch.path in viewed) Lucide.circleCheck else Lucide.square, 16f, Css.muted)
            add(Css.ghost(999) { if (!viewed.add(ch.path)) viewed.remove(ch.path); rebuild() }.apply { name = "已查看"; add(box) }).size(dp(36f))
            add(Css.iconButton(Lucide.externalLink, 14f, Css.muted, 999, "打开文件") { openInEditor(ch.path) }).size(dp(44f))
        }).growX().height(dp(58f)).row()
        if (open) {
            add(Css.rule()).growX().height(dp(1f)).row()
            add(diff(ch.diff)).growX().padTop(dp(8f)).padBottom(dp(8f))
        }
    }

    /** 差异行：48px 行号列（新增行 #d7eadc、绿色数字）+ 12px/19.5 代码（padding 0 12），新增行整行 14% 绿底。 */
    private fun diff(text: String) = Table().top().left().apply {
        var newLine = 0
        val body = text.lines().filterNot { it.startsWith("diff --git") || it.startsWith("--- ") || it.startsWith("+++ ") || it.startsWith("index ") }
        body.take(800).forEach { line ->
            val hunk = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)""").find(line)
            if (hunk != null) newLine = hunk.groupValues[1].toInt() - 1
            val added = line.startsWith("+")
            val removed = line.startsWith("-")
            val number = when { hunk != null || line.startsWith("new file") -> ""; removed -> ""; else -> (++newLine).toString() }
            val green = Css.green
            val red = RwTheme.Colors.diffRemoved
            val rowBg = when { added -> green.cpy().a(0.14f); removed -> red.cpy().a(0.12f); else -> null }
            val numBg = when { added -> Css.bg.cpy().lerp(green, 0.18f); removed -> Css.bg.cpy().lerp(red, 0.15f); else -> Css.bg }
            add(Table(rowBg?.let { RwTheme.solid(it) }).left().apply {
                add(Table(RwTheme.solid(numBg)).right().apply {
                    margin(0f, dp(8f), 0f, dp(8f))
                    add(Label(number, Label.LabelStyle(RwTheme.monoAt(12f, 19.5f), if (added) green else if (removed) red else Color.valueOf("737373"))))
                }).width(dp(48f)).growY()
                val code = Label(if (added || removed) line.substring(1) else line, Label.LabelStyle(RwTheme.monoAt(12f, 19.5f), Css.text))
                if (wrap) code.setWrap(true)
                add(code).growX().minWidth(0f).padLeft(dp(12f)).padRight(dp(12f))
            }).growX().minHeight(dp(19.5f)).row()
        }
    }

    private fun downloadPatch(changes: List<CodexFileChange>) {
        val patch = changes.joinToString("\n") { "--- a/${it.path}\n+++ b/${it.path}\n${it.diff}" }
        onExport("changes.patch", patch)
    }

    private fun openInEditor(path: String) {
        val p = project ?: return
        val rel = path.removePrefix(p.path).trimStart('/')
        host.openFile(p, rel)
    }

    // endregion

    // region 文件

    private fun files(): Table = Table().top().apply {
        add(Table().left().apply {
            margin(dp(12f), dp(16f), dp(8f), dp(16f))
            add(Css.label("项目文件", 15f, Css.text, 500, 22.5f)).growX().left()
            add(Css.iconButton(Lucide.refreshCw, 16f, Css.text, 999, "刷新文件") { rebuild() }).size(dp(36f))
            add(Css.iconButton(Lucide.upload, 16f, Css.text, 999, "上传文件") { onFiles(dir, "upload") }).size(dp(36f)).padLeft(dp(8f))
            add(Table(RwTheme.card(Css.bg, Css.border, 18)).apply {
                name = "新建文件"
                touchable = Touchable.enabled
                margin(0f, dp(12f), 0f, dp(12f))
                add(Css.icon(Lucide.filePlus, 16f)).padRight(dp(8f))
                add(Css.label("新建文件", 12f, Css.text, lineHeight = 16f))
                clicked { onFiles(dir, "new") }
            }).height(dp(36f)).padLeft(dp(8f))
        }).growX().row()
        // 筛选框：圆角 16.8、灰底 55%、左侧放大镜
        add(arc.scene.ui.layout.Stack().apply {
            add(Table(RwTheme.round(16, Css.hover.cpy().a(0.55f))))
            add(Table().left().apply {
                add(Css.icon(Lucide.search, 16f, Css.muted)).padLeft(dp(12f)).padRight(dp(12f))
                add(filter).growX().padRight(dp(12f))
            })
        }).growX().height(dp(40f)).pad(0f, dp(16f), dp(12f), dp(16f)).row()
        // 面包屑
        add(Table().left().apply {
            add(Css.ghost(12) { dir = ""; rebuild() }.apply {
                margin(0f, dp(8f), 0f, dp(8f))
                add(Css.icon(Lucide.folder, 14f)).padRight(dp(6f))
                add(Css.label("文件", 12f, Css.text, lineHeight = 16f))
            }).height(dp(28f))
            var acc = ""
            dir.split('/').filter { it.isNotEmpty() }.forEach { part ->
                acc = if (acc.isEmpty()) part else "$acc/$part"
                val target = acc
                add(Css.icon(Lucide.chevronRight, 14f, Css.muted))
                add(Css.ghost(12) { dir = target; rebuild() }.apply {
                    margin(0f, dp(8f), 0f, dp(8f))
                    add(Css.label(part, 12f, Css.text, lineHeight = 16f))
                }).height(dp(28f))
            }
        }).left().padLeft(dp(16f)).padBottom(dp(12f)).row()
        add(Css.rule()).growX().height(dp(1f)).row()
        val all = project?.let { runCatching { host.listFiles(it) }.getOrDefault(emptyList()) }.orEmpty()
        val q = filter.text.trim()
        val list = Table(RwTheme.solid(Css.bg)).top().left()
        list.margin(dp(8f), 0f, dp(8f), 0f)
        if (q.isNotEmpty()) {
            all.filter { it.contains(q, ignoreCase = true) }.take(300).forEach { list.add(fileRow(it, it, false, null)).growX().height(dp(46f)).row() }
        } else {
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            val under = all.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            val dirs = under.filter { '/' in it }.map { it.substringBefore('/') }.distinct().sorted()
            val filesHere = under.filter { '/' !in it }.sorted()
            dirs.forEach { d -> list.add(fileRow(d, prefix + d, true, null)).growX().height(dp(46f)).row() }
            filesHere.forEach { f -> list.add(fileRow(f, prefix + f, false, project?.let { host.fileSize(it, prefix + f) })).growX().height(dp(46f)).row() }
            if (dirs.isEmpty() && filesHere.isEmpty()) list.add(Css.para("没有文件。", 14f, Css.muted)).growX().pad(dp(16f)).row()
        }
        add(scroll(list)).grow()
    }

    /** 文件行：图标 18、名字 14px、文件夹带箭头 14、文件带大小 11px。 */
    private fun fileRow(name: String, path: String, isDir: Boolean, size: Long?) = Css.ghost(0) {
        if (isDir) { dir = path; filter.text = ""; rebuild() } else openInEditor((project?.path ?: "") + "/" + path)
    }.apply {
        left()
        this.name = "文件:$path"
        margin(0f, dp(20f), 0f, dp(20f))
        add(Css.icon(if (isDir) Lucide.folder else Lucide.fileCode, 18f)).padRight(dp(12f))
        add(Css.label(name, 14f, Css.text, lineHeight = 20f).apply { setEllipsis(true) }).growX().minWidth(0f)
        if (isDir) add(Css.icon(Lucide.chevronRight, 14f, Css.text))
        else size?.let { add(Css.label(formatSize(it), 11f, Css.muted)).padLeft(dp(8f)) }
    }

    private fun formatSize(b: Long) = when { b < 1024 -> "$b B"; b < 1024 * 1024 -> "${b / 1024} KB"; else -> "%.1f MB".format(b / 1048576.0) }

    // endregion

    // region 日志

    private fun logs(): Table {
        val commands = items.filter { it.type == "commandExecution" }
        return Table().top().apply {
            add(Table().left().apply {
                margin(dp(12f), dp(16f), dp(12f), dp(4f))
                add(Css.label("命令历史", 14f, Css.text, 500, 20f)).padRight(dp(8f))
                add(count(commands.size))
                add().growX()
                add(Css.iconButton(Lucide.download, 16f, Css.muted, 999, "下载日志") {
                    val text = commands.joinToString("\n\n") { "$ ${it.command}\n${it.output}" }
                    onExport("commands.log", text)
                }).size(dp(44f))
            }).growX().row()
            add(Css.rule()).growX().height(dp(1f)).row()
            val list = Table().top().left()
            list.margin(dp(16f))
            if (commands.isEmpty()) list.add(Css.para("这个对话还没有运行命令。", 14f, Css.muted)).growX().row()
            commands.forEach { list.add(logCard(it)).growX().padBottom(dp(12f)).row() }
            add(scroll(list)).grow()
        }
    }

    /** 命令卡片：圆角 16；页眉灰底 padding 12：箭头 / $ 命令 12px 等宽 / 状态行（✓ 已完成 退出码 0）；展开后工作目录 11px + 复制，输出等宽。 */
    private fun logCard(cmd: CodexItem) = Table(RwTheme.card(Css.bg, Css.border, 16)).top().left().apply {
        val key = "log:" + cmd.id
        val open = key !in collapsed
        add(Css.ghost(0) { if (!collapsed.add(key)) collapsed.remove(key); rebuild() }.apply {
            top().left()
            background(RwTheme.solid(if (RwTheme.dark) Color(1f, 1f, 1f, 0.04f) else Color.valueOf("f6f6f6")))
            margin(dp(12f))
            add(Css.icon(if (open) Lucide.chevronDown else Lucide.chevronRight, 14f)).top().padTop(dp(3f)).padRight(dp(10f))
            add(Table().top().left().apply {
                add(RichText(listOf(
                    RichText.Run("$ ", RwTheme.monoAt(12f), Css.muted),
                    RichText.Run(cmd.command.orEmpty(), RwTheme.monoAt(12f), Css.text),
                ), 20f)).growX().row()
                val ok = cmd.exitCode == 0 || cmd.status == "completed"
                add(Table().left().apply {
                    add(Css.icon(if (cmd.running) Lucide.loader else if (ok) Lucide.check else Lucide.x, 12f, Css.muted)).padRight(dp(6f))
                    add(Css.label(if (cmd.running) "运行中" else if (ok) "已完成" else "失败", 12f, Css.muted, lineHeight = 16f)).padRight(dp(8f))
                    cmd.exitCode?.let { add(Css.label("退出码 $it", 12f, Css.muted, lineHeight = 16f)) }
                }).left().padTop(dp(8f))
            }).growX().minWidth(0f)
        }).growX().row()
        if (open) {
            add(Css.rule()).growX().height(dp(1f)).row()
            cmd.cwd?.let { cwd ->
                add(Table().left().apply {
                    margin(dp(4f), dp(12f), 0f, dp(12f))
                    add(Label(cwd, Label.LabelStyle(RwTheme.monoAt(11f), Css.muted))).growX().left().minWidth(0f)
                    add(Css.ghost(999) { host.copy(cmd.output) }.apply {
                        name = "复制输出"
                        margin(0f, dp(10f), 0f, dp(10f))
                        add(Css.icon(Lucide.copy, 14f, Css.muted)).padRight(dp(6f))
                        add(Css.label("复制", 14f, Css.muted))
                    }).height(dp(32f))
                }).growX().row()
            }
            add(Label(cmd.output.lines().takeLast(200).joinToString("\n").ifBlank { "命令没有输出。" }, Label.LabelStyle(RwTheme.monoAt(12f, 20f), Css.text)).apply { setWrap(true) })
                .growX().pad(dp(8f), dp(12f), dp(12f), dp(12f)).row()
        }
    }

    // endregion
}
