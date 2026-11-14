package io.github.zenthxsin.rustwright.fused

import arc.scene.Element
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.codex.CodexItem
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.ImageThumb
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.ZoomImage
import io.github.zenthxsin.rustwright.ui.widgets.MarkdownView
import java.io.File

/**
 * 步骤详情里的「预览」：命令、输出、工具参数和结果里提到的项目文件（图片、地图、Markdown、文本），
 * 在输出下面直接显示缩略内容，点一下全屏查看。
 */
internal object StepPreview {
    private val pathRe = Regex("""[\w./\-一-龥\[\]]+\.(png|jpe?g|webp|gif|bmp|tmx|md|txt|ini|json|template)\b""", RegexOption.IGNORE_CASE)
    private val images = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    private const val MAX = 4

    /** 这一步提到的、在项目里真实存在的文件（最多 4 个）。 */
    fun files(s: FusedShell, i: CodexItem): List<File> {
        val texts = listOfNotNull(i.text, i.command, i.output, i.arguments, i.result)
        val raw = texts.flatMap { t -> pathRe.findAll(t).map { it.value } } + i.changes.map { it.path }
        return raw.mapNotNull { resolve(s, it) }.distinctBy { it.canonicalPath }.take(MAX)
    }

    /** 相对路径按项目目录解析；虚拟环境里的绝对路径（/workspace/...）换成本机路径。 */
    private fun resolve(s: FusedShell, raw: String): File? {
        val dir = s.host.ui?.projectDir() ?: return null
        val p = raw.trim('"', '\'', '(', ')', '`', ',')
        val guest = s.currentProject()?.path?.trimEnd('/')
        val f = when {
            guest != null && p.startsWith("$guest/") -> File(dir, p.removePrefix("$guest/"))
            p.startsWith("/") -> File(p)
            else -> File(dir, p)
        }
        return f.takeIf { runCatching { it.isFile && it.length() < 20L shl 20 }.getOrDefault(false) }
    }

    private fun ext(f: File) = f.extension.lowercase()

    /** 预览块：每个文件一张卡（缩略内容 + 文件名），点开全屏。 */
    fun block(s: FusedShell, files: List<File>): Element = Table().top().left().apply {
        files.forEachIndexed { i, f ->
            add(card(s, f)).growX().padTop(if (i == 0) 0f else dp(10f)).row()
        }
    }

    private fun card(s: FusedShell, f: File): Element {
        val t = Tap(K.bubble, 10f) { open(s, f) }
        t.name = "预览:${f.name}"
        t.margin(dp(10f))
        when {
            ext(f) in images -> t.add(ImageThumb(f, K.surface2)).growX().height(dp(180f)).row()
            s.host.filePresentation(f)?.preview != null -> t.add(s.host.filePresentation(f)!!.preview!!(f)).growX().height(dp(180f)).row()
            ext(f) == "md" -> t.add(MarkdownView(ChatView.mdStyle).apply { setMarkdown(head(f, 40)) }).growX().left().row()
            else -> t.add(K.mono(head(f, 24), 12f, K.muted, 18f).apply { setWrap(true) }).growX().left().row()
        }
        val name = Table()
        name.add(K.svg(icon(f), 14f, K.subtle, 1.8f)).padRight(dp(6f))
        name.add(K.text(f.name, 13f, K.subtle, lh = 13f).apply { setEllipsis(true) }).growX().minWidth(0f).left()
        name.add(K.svg(PI.chevronRight, 14f, K.subtle, 2f)).padLeft(dp(6f))
        t.add(name).growX().padTop(dp(8f))
        return t
    }

    private fun icon(f: File) = when {
        ext(f) in images -> PI.img
        else -> PI.file
    }

    private fun head(f: File, lines: Int): String = runCatching {
        f.bufferedReader().useLines { seq -> seq.take(lines).joinToString("\n") }
    }.getOrDefault("（读不出内容）")

    /** 点开：图片 / 地图全屏预览，INI 和其他文本交给 [FusedShell.openFile] 进对应的编辑器。 */
    fun open(s: FusedShell, f: File) {
        s.sheets.close()
        val page: Element = when {
            ext(f) in images -> Table().apply { add(ZoomImage(f)).grow().pad(dp(8f)) }
            s.host.filePresentation(f)?.open != null -> s.host.filePresentation(f)!!.open!!(f) ?: return s.openFile(f)
            else -> return s.openFile(f)
        }
        s.openTool(f.name, page, desc = f.path)
    }
}
