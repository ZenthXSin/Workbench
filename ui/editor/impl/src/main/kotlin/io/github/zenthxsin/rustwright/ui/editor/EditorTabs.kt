package io.github.zenthxsin.rustwright.ui.editor

import arc.Core
import arc.scene.Element
import arc.scene.ui.ScrollPane
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.PerfTrace
import io.github.zenthxsin.rustwright.ui.kit.reactive.*
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** Coordinates open documents, close/save behavior and tab visibility. */
internal class EditorTabs(scope: CoroutineScope, private val host: Host) {
    interface Host {
        fun current(): File?
        fun dirty(): Boolean
        fun errors(): Int
        fun open(file: File?)
        fun save(after: () -> Unit)
        fun leave()
        fun changed()
    }

    private var files by signal(emptyList<File>())
    private val revision = signal(0)
    private var revealed: File? = null
    val strip = EditorTabStrip(scope, object : EditorTabStrip.Host {
        override fun openFiles() = files.filter { it.isFile }
        override fun current() = host.current()
        override fun dirty(f: File) = f == host.current() && host.dirty()
        override fun errors(f: File) = if (f == host.current()) host.errors() else 0
        override fun select(f: File) = selectFile(f)
        override fun close(f: File) = this@EditorTabs.close(f)
        override fun closeOthers(keep: File) {
            files = files.filter { it == keep }
            selectFile(keep)
        }
        override fun closeAll() = afterSave { files = emptyList(); host.open(null) }
        override fun changed() {
            revealed = null
            revision.value = revision.peek() + 1
            host.changed()
        }
    })
    val pane = ScrollPane(strip, ScrollPane.ScrollPaneStyle()).apply {
        setScrollingDisabledY(true)
        setOverscroll(true, false)
    }

    fun observe() { files; revision.value }
    fun opened(file: File) { if (file !in files) files = (files + file).takeLast(EditorPage.MAX_TABS) }

    fun retainProject(root: File?) {
        val belongs = { file: File -> root != null && file.startsWith(root) }
        files = files.filter(belongs)
        strip.bookmarks.retainAll(belongs)
        strip.groups.forEach { it.files.retainAll(belongs) }
        strip.groups.removeAll { it.files.isEmpty() }
    }

    fun close(file: File) {
        val index = files.indexOf(file)
        if (index < 0) return
        if (file != host.current()) { files -= file; return }
        val next = files.getOrNull(index + 1) ?: files.getOrNull(index - 1)
        afterSave { files -= file; if (next != null) host.open(next) else host.leave() }
    }

    private fun selectFile(file: File) {
        if (file != host.current()) afterSave { host.open(file) }
    }
    private fun afterSave(action: () -> Unit) { if (host.dirty()) host.save(action) else action() }

    fun rebuild() = PerfTrace.span("tabs.rebuild") { strip.rebuild(); revealCurrent() }
    private fun revealCurrent() {
        val file = host.current() ?: return
        if (file == revealed) return
        revealed = file
        Core.app.post {
            pane.layout()
            val target = strip.groupOf(file)?.let { strip.find<Element>("标签组 " + it.name) }
                ?: strip.find<Element>("标签 " + file.name) ?: return@post
            pane.scrollTo(target.x - dp(10f), 0f, target.width + dp(20f), pane.height)
        }
    }
}
