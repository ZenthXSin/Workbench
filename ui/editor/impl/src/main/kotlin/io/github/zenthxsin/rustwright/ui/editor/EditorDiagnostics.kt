package io.github.zenthxsin.rustwright.ui.editor

import arc.Core
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.editor.api.*
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.*
import io.github.zenthxsin.rustwright.ui.widgets.code.CodeEditor
import kotlinx.coroutines.*

/** Debounced checks own their cancellation and reject results from previous documents. */
internal class EditorDiagnostics(
    private val scope: CoroutineScope,
    private val editor: CodeEditor,
    private val host: () -> EditorHost,
    private val support: () -> EditorSupport,
    private val attached: () -> Boolean,
    private val showIssues: () -> Unit,
    private val apply: (List<EditorDiagnostic>) -> Unit,
) {
    val badge = Table()
    var checkedVersion = -1
        private set
    private var shownCounts = -3 to -3
    private var generation = 0
    private var job: Job? = null

    fun pause() {
        generation++
        job?.cancel()
        checkedVersion = -1
    }

    fun lint() {
        val file = host().file ?: return
        val root = host().root ?: return
        if (!file.startsWith(root)) return
        job?.cancel()
        val request = ++generation
        val document = editor.doc
        val version = document.version
        checkedVersion = version
        val lines = document.linesSnapshot()
        val language = support()
        job = scope.launch {
            delay(700)
            val text = withContext(Dispatchers.Default) { lines.joinToString("\n") }
            val result = try { language.problems(root, file, text) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@launch }
            Core.app.post {
                if (request != generation || !attached() || host().file != file || host().root != root || document.version != version) return@post
                apply(result)
                editor.marks = result.filter { it.line > 0 && it.severity in setOf("error", "warn") }
                    .mapNotNull { EditorDiagnosticMarks.of(it, lines) }
                setCounts(result.count { it.severity == "error" }, result.count { it.severity == "warn" })
            }
        }
    }

    /** 文件行右侧的问题计数；-2 不显示，-1 检查中。 */
    fun setCounts(errors: Int, warns: Int) {
        if (shownCounts == errors to warns) return
        shownCounts = errors to warns
        badge.clearChildren()
        if (errors == -2) return
        val t = Tap(null, 6f) { showIssues() }
        t.name = "问题"
        t.margin(0f, dp(4f), 0f, dp(4f))
        when {
            errors < 0 -> t.add(K.text("检查中…", 12f, K.subtle, lh = 14f))
            errors == 0 && warns == 0 -> t.add(K.svg(PI.check, 12f, K.green, 2f))
            else -> {
                if (errors > 0) t.add(K.text("错误 $errors", 12f, EditorColors.ERROR, 500, 14f)).padRight(if (warns > 0) dp(6f) else 0f)
                if (warns > 0) t.add(K.text("警告 $warns", 12f, EditorColors.WARN, 500, 14f))
            }
        }
        badge.add(t)
    }

}
