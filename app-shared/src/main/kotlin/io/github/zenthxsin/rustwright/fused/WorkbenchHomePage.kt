package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.codex.CodexProject
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.Tap

internal class WorkbenchHomePage(
    private val projects: ProjectServices,
    private val account: AccountServices,
    private val currentProject: () -> CodexProject?,
    private val writeDraft: (String) -> Unit,
    private val launch: (suspend () -> Unit) -> Unit,
) : Table() {
    private val cell: arc.scene.ui.layout.Cell<Table>
    private var greetCell: arc.scene.ui.layout.Cell<Table>? = null
    private var greetKey = ""

    override fun act(delta: Float) {
        super.act(delta)
        // 账号名是异步取回的，到了再换上「晚上好，名字」；跨过时段也更新
        val key = account.accountEmail + "|" + java.time.LocalTime.now().hour
        if (key != greetKey) {
            greetKey = key
            greetCell?.setElement(greetingRow())
        }
        // 换项目 / 新建了第一个项目：起步入口跟着变
        val pk = currentProject()?.id ?: ""
        if (pk != startersKey) { startersKey = pk; buildStarters() }
    }

    private val lead = K.para("", 15f, K.muted, lh = 24f)
    private val starters = Table().left()
    private var startersKey = "\u0000"

    /**
     * 起步入口：第一次打开的人不知道能说什么。没有项目时先引导新建；有项目时给四件做模组最常做的事，
     * 点一下把开头填进输入框（不直接发送，让人接着写具体要求）。
     */
    private fun buildStarters() {
        starters.clearChildren()
        lead.setText(if (currentProject() == null) "先建一个项目，再跟${AppBrand.identity.assistant}说想做什么。" else "描述你想实现的功能，或从下面开始。")
        fun row(icon: String, text: String, name: String, action: () -> Unit) {
            val t = Tap(null, 10f) { action() }
            t.name = name
            t.left()
            t.add(K.svg(icon, 18f, K.muted, 1.7f)).size(dp(18f)).padRight(dp(12f))
            t.add(K.text(text, 15f, K.text)).left().growX()
            // 行尾箭头：看得出这一行能点
            t.add(K.svg(PI.chevronRight, 16f, K.subtle, 2f)).size(dp(16f))
            starters.add(t).growX().height(dp(44f)).row()
        }
        fun say(text: String) = writeDraft(text)
        if (currentProject() == null) {
            // 还没有项目时这是唯一要做的事：做成实心主按钮
            val b = Tap(K.light, 12f) { launch { projects.createProject()?.let { projects.setCurrentProject(it.id) } } }
            b.name = "起步:新建项目"
            b.add(K.svg(PI.plus, 18f, K.onLight, 1.9f)).size(dp(18f)).padRight(dp(8f))
            b.add(K.text("新建项目", 16f, K.onLight, 600))
            b.margin(0f, dp(18f), 0f, dp(20f))
            starters.add(b).left().height(dp(48f)).padTop(dp(4f)).row()
            return
        }
        val contributed = projects.projectStarters(currentProject()!!)
        if (contributed.isEmpty()) {
            row(PI.code, "实现一个功能", "起步:功能") { say("实现一个功能：") }
            row(PI.shield, "检查这个项目", "起步:检查") { say("检查这个项目，列出需要修改的地方") }
        } else contributed.forEach { starter ->
            row(starter.icon, starter.title, "起步:${starter.id}") { say(starter.prompt) }
        }
    }

    init {
        val col = Table().left()
        col.add(io.github.zenthxsin.rustwright.ui.kit.AnvilLogo(40f)).left().row()
        greetCell = col.add(greetingRow()).left().padTop(dp(14f))
        col.row()
        col.add(lead).growX().left().padTop(dp(14f))
        col.row()
        col.add(starters).growX().left().padTop(dp(18f))
        cell = add(col).pad(0f, dp(28f), 0f, dp(28f))
    }

    override fun layout() {
        // 换行段落需要确定宽度
        cell.width((width - dp(56f)).coerceAtLeast(0f))
        super.layout()
    }

    /** 「晚上好，Zenth」：中文用黑体，拉丁名字用品牌衬线体（与原型在手机上的回退效果一致）。 */
    private fun greetingRow(): Table {
        val h = java.time.LocalTime.now().hour
        val part = when (h) { in 5..10 -> "早上好"; in 11..13 -> "中午好"; in 14..17 -> "下午好"; else -> "晚上好" }
        val name = account.accountEmail.substringBefore('@').trim()
        val row = Table()
        if (name.isEmpty()) {
            row.add(K.text(part, 34f, K.text, 500))
            return row
        }
        // 行尾全角逗号：Arc 按最后一个字形的墨迹宽度量行宽，逗号右半边的留白会丢，补回来
        val head = K.text("$part，", 34f, K.text, 500)
        val g = head.style.font.data.getGlyph('，')
        val lost = if (g != null) maxOf(0f, (g.xadvance - g.xoffset - g.width) * head.style.font.data.scaleX) else 0f
        row.add(head).bottom().padRight(lost)
        val latin = name.all { it.code < 0x2E80 }
        row.add(if (latin) K.serif(name, 34f, K.text, 500) else K.text(name, 34f, K.text, 500)).bottom()
        return row
    }
}
