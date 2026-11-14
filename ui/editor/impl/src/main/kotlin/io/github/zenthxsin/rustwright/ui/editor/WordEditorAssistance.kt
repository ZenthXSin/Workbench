package io.github.zenthxsin.rustwright.ui.editor
import arc.Core
import kotlinx.coroutines.*
import arc.input.KeyCode
import arc.scene.event.InputEvent
import arc.scene.event.InputListener
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.editor.api.EditorAssistance
import io.github.zenthxsin.rustwright.ui.kit.*
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.widgets.code.*

class WordEditorAssistance(private val editor: CodeEditor, private val tip: Table, private val scope: CoroutineScope) : EditorAssistance {
    override val candidates = Table().left()
    override val isOpen get() = candidates.visible
    private var job: Job? = null
    private var disposed = false
    private var generation = 0
    private var revision = -1; private var cursor = Pos(-1, -1); private var start = 0
    private var words = emptyList<String>(); private var selected = 0; private var explicit = false
    private val dictionary = setOf("true", "false", "null", "return", "function", "class", "import", "const", "let", "var", "if", "else", "for", "while", "public", "private", "fun", "val", "def", "async", "await", "None", "self")
    private val listener = object : InputListener() {
        override fun keyDown(event: InputEvent, keycode: KeyCode): Boolean {
            if (!isOpen) return false
            when (keycode) {
                KeyCode.up -> { selected = (selected - 1).coerceAtLeast(0); build(); return true }
                KeyCode.down -> { selected = (selected + 1).coerceAtMost(words.lastIndex); build(); return true }
                KeyCode.escape -> { close(); return true }
                else -> return false
            }
        }
    }
    init {
        candidates.visible = false
        editor.onTab = { acceptSelected() }; editor.onEnter = { close(); false }
        editor.addCaptureListener(listener)
    }
    override fun act() {
        if (!editor.editable) { close(); return }
        if (revision == editor.doc.version && cursor == editor.cursor) return
        val changed = revision != editor.doc.version
        revision = editor.doc.version; cursor = editor.cursor
        val line = editor.doc.line(cursor.line); start = cursor.col.coerceAtMost(line.length)
        while (start > 0 && (line[start - 1].isLetterOrDigit() || line[start - 1] == '_')) start--
        val prefix = line.substring(start, cursor.col.coerceAtMost(line.length))
        if ((!changed && !explicit) || (prefix.length < 2 && !explicit)) { close(); return }
        val text = editor.doc.text; val expectedVersion = revision; val expectedCursor = cursor; val token = ++generation
        job?.cancel(); candidates.visible = false; editor.ghost = null
        job = scope.launch(Dispatchers.Default) {
            delay(80)
            val found = (dictionary + Regex("[A-Za-z_][A-Za-z_0-9]{2,}").findAll(text).take(20000).map { it.value }.toSet())
                .filter { it.startsWith(prefix, true) && it != prefix }.sorted().take(12)
            Core.app.post {
                if (!disposed && token == generation && editor.editable && editor.doc.version == expectedVersion && editor.cursor == expectedCursor) {
                    words = found; selected = 0; candidates.visible = words.isNotEmpty(); build()
                    editor.ghost = words.firstOrNull()?.drop(prefix.length)?.takeIf { expectedCursor.col == line.length }
                    tip.clearChildren(); tip.visible = false
                }
            }
        }
        explicit = false
    }

    private fun build() {
        candidates.clearChildren()
        words.forEachIndexed { i, word -> candidates.add(Tap(if (i == selected) K.surface3 else null, 6f) { selected = i; acceptSelected() }.apply {
            name = "补全 $word"; margin(dp(8f), dp(16f), dp(8f), dp(16f)); add(K.mono(word, 14f, K.text))
        }).growX().row() }
    }
    override fun open() { explicit = true; revision = -1; act() }
    override fun acceptSelected(): Boolean {
        val word = words.getOrNull(selected) ?: return false
        if (!isOpen || !editor.editable) return false
        val c = editor.cursor; editor.replace(Pos(c.line, start), c, word); close(); return true
    }
    private fun close() { generation++; job?.cancel(); candidates.visible = false; editor.ghost = null; explicit = false }
    override fun dispose() { disposed = true; close(); editor.removeCaptureListener(listener); editor.onTab = null; editor.onEnter = null }
}
