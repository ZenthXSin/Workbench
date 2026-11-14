package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import io.github.zenthxsin.rustwright.ui.kit.PageBar
import io.github.zenthxsin.rustwright.ui.kit.pageBar
import io.github.zenthxsin.rustwright.ui.kit.OwnHeader
import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Shapes
import arc.scene.Element
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.ListGroup
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.kit.listRow
import io.github.zenthxsin.rustwright.ui.kit.sectionTitle
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 环境管理（「更多 → 环境管理」）：虚拟环境（Alpine）的状态 / 安装进度，装了哪些软件（版本），占多少空间，
 * 以及「打开终端」「修复环境」「复制诊断报告」。
 */
class EnvToolPage(
    private val env: LinuxEnvironment,
    private val provisioner: LinuxProvisioner?,
    private val dataDir: File,
    private val project: () -> ProjectContext?,
    private val scope: CoroutineScope,
) : Table(), OwnHeader {
    private val statusCard = Table()
    private val software = Table().top()
    private val storage = Table().top()
    private var versions: Map<String, String?>? = null
    private var rootfsBytes: Long? = null
    private var shownState = ""

    private val bar = PageBar("环境管理") { "Alpine Linux · " + when { provisioner?.running == true -> "正在安装"; provisioner?.error != null -> "安装失败"; !env.installed -> "未安装"; provisioner != null && !provisioner.ready -> "未完成"; else -> "已就绪" } }
    override fun bindBack(back: () -> Unit) = bar.bindBack(back)

    init {
        top()
        pageBar(bar)
        bar.action(PI.term, "打开终端") { project()?.openTool("terminal") }
        bar.action(PI.refresh, "修复环境") { repair() }
        val col = Table().top()
        col.margin(dp(4f), dp(20f), dp(24f), dp(20f))
        col.add(statusCard).growX().row()
        col.add(sectionTitle("软件")).left().pad(dp(22f), dp(4f), dp(8f), dp(4f)).row()
        col.add(software).growX().row()
        col.add(sectionTitle("存储")).left().pad(dp(22f), dp(4f), dp(8f), dp(4f)).row()
        col.add(storage).growX().row()
        val diag = ListGroup()
        diag.row(listRow("复制诊断报告", "安装日志的末尾和失败原因，发给开发者排查", PI.copy, K.muted, minHeight = 60f) { copyReport() })
        col.add(diag).growX().padTop(dp(22f)).row()
        add(ScrollPane(col).apply { setScrollingDisabledX(true); setOverscroll(false, true) }).grow()
        rebuild()
        load()
    }

    override fun act(delta: Float) {
        super.act(delta)
        val st = stateKey()
        if (st != shownState) rebuild()
    }

    private fun stateKey(): String {
        val p = provisioner
        return when {
            !env.installed -> "none:" + (p?.running ?: false) + (p?.step?.name ?: "") + (p?.error ?: "") + ((p?.progress ?: 0f) * 100).toInt()
            p != null && p.running -> "run:" + (p.step?.name ?: "") + (p.progress * 100).toInt() + p.detail
            p != null && p.error != null -> "err:" + p.error
            p != null && !p.ready -> "partial"
            else -> "ready"
        }
    }

    private fun rebuild() {
        shownState = stateKey()
        val p = provisioner
        statusCard.clearChildren()
        val card = Table(K.fill(K.surface, 14f))
        card.margin(dp(16f))
        val (label, color, sub) = when {
            p != null && p.running -> Triple("正在安装", K.c("E9A23B"), (p.step?.label ?: "准备中") + (p.detail.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
            p != null && p.error != null -> Triple("安装失败", K.danger, p.error!!.lineSequence().first().take(120))
            !env.installed -> Triple("未安装", K.subtle, "首次使用会自动安装，装好约 600 MB")
            p != null && !p.ready -> Triple("未完成", K.c("E9A23B"), "有组件没装好，点「修复环境」补装")
            else -> Triple("已就绪", K.green, "git、node、python 和${AppBrand.identity.assistant}工具都可以用；Java 要用时再装")
        }
        val head = Table()
        head.add(Table(K.fill(K.surface3, 12f)).apply { add(K.svg(PI.server, 22f, K.text, 1.7f)) }).size(dp(44f)).padRight(dp(14f))
        val t = Table().left()
        t.add(K.text("Alpine Linux", 17f, K.text, 600, 17f)).left().row()
        val st = Table()
        st.add(Dot(color)).size(dp(8f)).padRight(dp(6f))
        st.add(K.text(label, 14f, color, lh = 14f))
        t.add(st).left().padTop(dp(6f))
        head.add(t).growX().left()
        card.add(head).growX().row()
        card.add(K.para(sub, 14f, K.subtle, lh = 20f)).growX().left().padTop(dp(12f)).row()
        if (p != null && p.running) card.add(Bar(p.progress)).growX().height(dp(4f)).padTop(dp(12f))
        statusCard.add(card).growX()

        software.clearChildren()
        val v = versions
        val g = ListGroup()
        val names = listOf("Java", "Node.js", "Python", "Git", "Codex", "rw-modding-mcp")
        for (n in names) {
            val ver = v?.get(n)
            // Java 默认不装，没装时这一行就是安装入口
            val installable = n == "Java" && v != null && ver == null && env.installed && provisioner?.ready == true
            val trailing = when { n == "Java" && installingJava -> "安装中…"; v == null -> "读取中…"; installable -> "安装"; ver == null -> "未安装"; else -> ver }
            g.row(listRow(n, if (installable && !installingJava) "约 300 MB，含 javac" else null, null, minHeight = 54f, trailing = trailing,
                chevron = installable && !installingJava, onClick = if (installable && !installingJava) ({ installJava() }) else null))
        }
        software.add(g).growX()

        storage.clearChildren()
        val sg = ListGroup()
        sg.row(listRow("系统占用", null, null, minHeight = 54f, trailing = rootfsBytes?.let { gb(it) } ?: if (env.installed) "统计中…" else "—"))
        sg.row(listRow("手机可用空间", null, null, minHeight = 54f, trailing = gb(dataDir.usableSpace)))
        storage.add(sg).growX()
    }

    /** 一条命令读所有版本，每行「名字=版本」；读不到的为空（显示未安装）。 */
    private fun load() {
        if (!env.installed) { versions = emptyMap(); rebuild(); return }
        scope.launch {
            val cmd = listOf(
                "Java" to "java -version 2>&1 | head -1 | sed -E 's/.*\"([^\"]+)\".*/\\1/'",
                "Node.js" to "node -v",
                "Python" to "python3 -V 2>&1 | cut -d' ' -f2",
                "Git" to "git --version | cut -d' ' -f3",
                "Codex" to "codex --version 2>/dev/null | awk '{print \$NF}'",
                "rw-modding-mcp" to "test -d /opt/rw-modding-mcp && (git -C /opt/rw-modding-mcp rev-parse --short HEAD 2>/dev/null || echo 已安装)",
            ).joinToString("; ") { (n, c) -> "printf '%s=' '$n'; ($c) 2>/dev/null | head -1; echo" }
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val proc = env.start(cmd)
                    val text = proc.inputStream.bufferedReader().readText()
                    proc.waitFor(20, TimeUnit.SECONDS)
                    text
                }.getOrDefault("")
            }
            val map = LinkedHashMap<String, String?>()
            out.lineSequence().filter { '=' in it }.forEach { l -> map[l.substringBefore('=')] = l.substringAfter('=').trim().ifEmpty { null } }
            val size = withContext(Dispatchers.IO) {
                runCatching { env.toHost("/").walkTopDown().onEnter { !it.name.startsWith("proc") && it.name != "sys" && it.name != "dev" }.filter { it.isFile }.sumOf { it.length() } }.getOrNull()
            }
            Core.app.post { versions = map; rootfsBytes = size; rebuild() }
        }
    }

    @Volatile private var installingJava = false

    private fun installJava() {
        val p = provisioner ?: return
        scope.launch {
            if (!Dialogs.confirm("安装 Java 21", "下载约 300 MB，装好后可以用 java 和 javac。过程会显示在终端的「${AppBrand.identity.assistant}」会话里。", ok = "安装")) return@launch
            Core.app.post { installingJava = true; rebuild() }
            val ok = runCatching { p.installJava() }.getOrDefault(false)
            Core.app.post {
                installingJava = false
                project()?.notify(if (ok) "Java 21 已安装" else "Java 安装失败，去终端看看原因")
                versions = null
                rebuild()
                load()
            }
        }
    }

    private fun repair() {
        val p = provisioner ?: return project()?.notify("这个版本没有虚拟环境") ?: Unit
        if (p.running) return project()?.notify("正在安装，稍等") ?: Unit
        scope.launch {
            if (!Dialogs.confirm("修复环境", "会重新跑一遍初始化：补装缺少的软件、修复损坏的依赖。项目文件和配置会保留，大约需要几分钟。", ok = "开始修复")) return@launch
            Core.app.post { p.repair(); versions = null; rebuild() }
        }
    }

    private fun copyReport() {
        val ctx = project()
        val report = provisioner?.failureReport()?.takeIf { it.isNotBlank() }
            ?: "环境状态：${if (env.installed) "已安装" else "未安装"} / ${shownState}\n" + (versions?.entries?.joinToString("\n") { "${it.key}: ${it.value ?: "未安装"}" } ?: "")
        Core.app.clipboardText = report
        ctx?.notify("已复制诊断报告")
    }

    private fun gb(b: Long) = if (b >= 1L shl 30) "%.1f GB".format(b / 1073741824f) else "%.0f MB".format(b / 1048576f)

    private class Dot(private val c: Color) : Element() {
        override fun draw() = Shapes.circle(x + width / 2f, y + height / 2f, width / 2f, Color(c).a(parentAlpha * color.a))
    }

    private class Bar(private val p: Float) : Element() {
        override fun draw() {
            val a = parentAlpha * color.a
            Shapes.rect(x, y, width, height, height / 2f, Color(K.inputBorder).a(a))
            Shapes.rect(x, y, width * p.coerceIn(0f, 1f), height, height / 2f, Color(K.accent).a(a))
        }
    }
}
