package io.github.zenthxsin.rustwright.ui.editor

import io.github.zenthxsin.rustwright.ui.editor.EditorChrome

import arc.graphics.Color
import arc.scene.Element
import arc.scene.event.ElementGestureListener
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.Tap
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 编辑器标签栏：每个标签带 ×；长按（电脑右键）出菜单：加入书签、新建组（先起名）、移到组 / 移出组、关闭其他、关闭全部。
 * 组只占一格（彩色文件夹 + 数量），点开向下展开组里的标签。
 * TODO：把标签拖到组上放进去（现在用长按菜单「移到「组」」）。
 */
class EditorTabStrip(private val scope: CoroutineScope, private val host: Host) : Table() {
    interface Host {
        fun openFiles(): List<File>
        fun current(): File?
        fun dirty(f: File): Boolean
        fun errors(f: File): Int
        fun select(f: File)
        fun close(f: File)
        fun closeOthers(keep: File)
        fun closeAll()
        fun changed()
    }

    class Group(var name: String, val color: Color, val files: MutableList<File>)

    val bookmarks = ArrayList<File>()
    val groups = ArrayList<Group>()
    private val palette = listOf("7AA7F0", "E8A070", "9FD3A8", "C9B6D6")

    init { left() }

    fun groupOf(f: File) = groups.firstOrNull { f in it.files }

    fun rebuild() {
        clearChildren()
        margin(0f, dp(10f), 0f, dp(10f))
        val open = host.openFiles()
        groups.removeAll { g -> g.files.retainAll(open.toSet()); g.files.isEmpty() }
        for (g in groups) add(groupChip(g)).height(dp(30f)).padRight(dp(4f))
        for (f in open) if (groupOf(f) == null) add(tab(f, null)).height(dp(30f)).padRight(dp(4f))
    }

    private fun tab(f: File, g: Group?): Table {
        val on = f == host.current()
        var long = false
        val t = object : Tap(if (on) K.surface2 else null, 8f, { if (long) long = false else host.select(f) }) {
            override fun draw() { super.draw(); g?.let { arc.graphics.g2d.Shapes.rect(x + dp(6f), y, width - dp(12f), dp(2f), dp(1f), Color(it.color).a(parentAlpha), 0f, Color.clear) } }
        }
        t.name = "标签 " + f.name
        t.margin(0f, dp(10f), 0f, dp(4f))
        if (host.dirty(f)) t.add(Table(K.fill(K.text, 3f))).size(dp(6f)).padRight(dp(6f))
        t.add(K.text(f.name, 13f, if (on) K.text else K.muted, lh = 13f)).padBottom(dp(1.5f))
        val errs = host.errors(f)
        if (errs > 0) t.add(Table(K.fill(K.dl("5A2A25", "F6D9D4"), 8f)).apply { add(K.text("$errs", 10.5f, EditorColors.ERROR, 600, 10.5f)) }).minSize(dp(16f)).padLeft(dp(6f))
        t.add(Tap(null, 6f) { host.close(f) }.apply { name = "关闭 " + f.name; add(K.svg(PI.close, 11f, K.subtle, 2f)) }).size(dp(24f)).padLeft(dp(2f))
        onHold(t, { long = true }) { tabMenu(t, f) }
        return t
    }

    private fun groupChip(g: Group): Table {
        val curIn = host.current() in g.files
        var long = false
        lateinit var chip: Tap
        chip = Tap(Color(g.color).a(if (curIn) 0.28f else 0.12f), 8f) { if (long) long = false else groupDropdown(chip, g) }
        chip.name = "标签组 " + g.name
        chip.margin(0f, dp(10f), 0f, dp(8f))
        chip.add(K.svg(PI.folder, 14f, g.color, 1.9f)).padRight(dp(6f))
        chip.add(K.text(g.name, 13f, K.text, lh = 13f)).padBottom(dp(1.5f))
        chip.add(K.text(" ${g.files.size}", 11f, K.subtle, lh = 11f))
        chip.add(K.svg(PI.chevronDown, 11f, K.subtle, 2.2f)).padLeft(dp(4f))
        onHold(chip, { long = true }) { groupMenu(chip, g) }
        return chip
    }

    /** 长按 0.45 秒（Arc 默认 1.1 秒太久）或电脑右键 → 菜单；[mark] 让随后的抬手不再当成点击。 */
    private fun onHold(e: Element, mark: () -> Unit, menu: () -> Unit) {
        e.addListener(object : ElementGestureListener(20f, 0.4f, 0.45f, 0.15f) {
            override fun longPress(actor: Element, x: Float, y: Float): Boolean { mark(); menu(); return true }
        })
        e.addListener(object : arc.scene.event.InputListener() {
            override fun touchDown(event: arc.scene.event.InputEvent, x: Float, y: Float, pointer: Int, button: arc.input.KeyCode): Boolean {
                if (button != arc.input.KeyCode.mouseRight) return false
                mark(); menu(); event.stop(); return true
            }
        })
    }

    /** 组：向下展开组里的标签（菜单同款淡入下移动画）。 */
    private fun groupDropdown(anchor: Element, g: Group) {
        val cur = host.current()
        EditorChrome.menu(anchor, g.files.map { f ->
            EditorChrome.Entry(f.name + (if (host.dirty(f)) " •" else ""), PI.code, checked = if (f == cur) true else null) { host.select(f) }
        } + EditorChrome.Entry("把当前标签放进来", PI.plus, enabled = cur != null && cur !in g.files) { cur?.let { moveTo(it, g) } })
    }

    private fun tabMenu(anchor: Element, f: File) {
        val g = groupOf(f)
        val marked = f in bookmarks
        val entries = ArrayList<EditorChrome.Entry>()
        entries += EditorChrome.Entry(if (marked) "移出书签" else "加入书签", EditorSidebar.STAR) { if (marked) bookmarks -= f else bookmarks += f; host.changed() }
        entries += EditorChrome.Entry("新建组", PI.folder) { newGroup(f) }
        for (other in groups) if (other !== g) entries += EditorChrome.Entry("移到「${other.name}」", PI.folder) { moveTo(f, other) }
        if (g != null) entries += EditorChrome.Entry("移出「${g.name}」", PI.folder) { g.files -= f; host.changed() }
        entries += EditorChrome.Entry("关闭其他标签", PI.copy, enabled = host.openFiles().size > 1) { host.closeOthers(f) }
        entries += EditorChrome.Entry("关闭全部标签", PI.trash) { host.closeAll() }
        EditorChrome.menu(anchor, entries)
    }

    private fun groupMenu(anchor: Element, g: Group) {
        EditorChrome.menu(anchor, listOf(
            EditorChrome.Entry("重命名", PI.edit) { askName("重命名组", g.name) { g.name = it; host.changed() } },
            EditorChrome.Entry("全部加入书签", EditorSidebar.STAR) { g.files.forEach { if (it !in bookmarks) bookmarks += it }; host.changed() },
            EditorChrome.Entry("解散组", PI.folder) { groups -= g; host.changed() },
            EditorChrome.Entry("关闭组里的标签", PI.trash) { g.files.toList().forEach { host.close(it) } },
        ))
    }

    private fun moveTo(f: File, g: Group) { groups.forEach { it.files -= f }; g.files += f; host.changed() }

    private fun newGroup(f: File) = askName("新建组", "") { name ->
        groups.forEach { it.files -= f }
        groups += Group(name, Color.valueOf(palette[groups.size % palette.size]), mutableListOf(f))
        host.changed()
    }

    /** 起名框：默认就弹出来，名字不能空、不能和已有的组重名。 */
    private fun askName(title: String, initial: String, done: (String) -> Unit) {
        scope.launch(ArcDispatcher) {
            val raw = Dialogs.input(title, "请输入分组名字", initial)?.trim() ?: return@launch
            when {
                raw.isEmpty() -> return@launch
                groups.any { it.name == raw && it.name != initial } -> Dialogs.choose("名字重复", "已经有叫「$raw」的组了", listOf("知道了"))
                else -> done(raw)
            }
        }
    }
}
