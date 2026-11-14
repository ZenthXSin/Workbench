package io.github.zenthxsin.rustwright.ui.editor

import arc.scene.Element
import arc.scene.event.InputEvent
import arc.scene.event.InputListener
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.editor.impl.ConfigurationFields
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.editor.api.*
import io.github.zenthxsin.rustwright.ui.kit.*
import io.github.zenthxsin.rustwright.ui.kit.reactive.*
import io.github.zenthxsin.rustwright.ui.widgets.code.CodeEditor
import io.github.zenthxsin.rustwright.ui.widgets.code.Pos
import kotlinx.coroutines.*

/** Owns field controls, candidate presentation and the detail drawer lifecycle. */
internal class EditorFieldPanel(
    private val editor: CodeEditor,
    private val scope: CoroutineScope,
    private val host: () -> EditorHost,
    private val support: () -> EditorSupport,
    private val refresh: () -> Unit,
) {
    data class State(val line: Int, val tipVisible: Boolean, val problems: List<EditorDiagnostic>, val candidates: Table?)

    val tip = Table().apply { visible = false }
    private val controls = Table().left()
    private val fieldCard = Table().top()
    val drawer = EditorInfoDrawer(fieldCard)
    val drawerHint = K.svg(PI.chevronDown, 14f, K.subtle, 2f).apply { visible = false }
    private val drawerEmpty = K.text("把光标放到字段上，这里显示它的说明和可调的控件", 12f, K.subtle, lh = 16f).apply { setWrap(true) }
    private val problemLine = K.text("", 13.5f, EditorColors.ERROR, lh = 20f).apply { setWrap(true) }
    private var dismissedLine = -1
    private var controlsKey = ""
    private var controlsJob: Job? = null
    private var cardRows = ""
    private var cardNow: (() -> Unit)? = null
    private val fields = ConfigurationFields(editor.doc) { from, to, text ->
        val cursor = editor.cursor
        editor.replace(from, to, text)
        if (from.line == to.line && '\n' !in text) {
            editor.setCursor(Pos(cursor.line, cursor.col.coerceAtMost(editor.doc.line(cursor.line).length)))
        }
    }
    private val doc get() = editor.doc

    init {
        drawer.onToggle = { open -> drawerHint.path = if (open) PI.chevronUp else PI.chevronDown }
        drawer.onSettled = { if (host().file != null) editor.ensureCursorVisible() }
        drawer.onUserClose = { dismissedLine = editor.cursor.line }
        drawer.onShown = { height -> if (host().file != null) editor.setTopInset(height) }
    }

    fun installHandle(row: Element) {
        row.name = "字段抽屉"
        row.touchable = arc.scene.event.Touchable.enabled
        row.addListener(object : InputListener() {
            private var startY = 0f
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: arc.input.KeyCode): Boolean {
                startY = event.stageY
                return true
            }
            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: arc.input.KeyCode) {
                val distance = event.stageY - startY
                val open = when {
                    distance < -dp(10f) -> true
                    distance > dp(10f) -> false
                    else -> !drawer.isOpen
                }
                if (open) { dismissedLine = -1; cardNow?.invoke(); drawer.open() } else dismiss()
            }
        })
    }

    fun dismiss() { dismissedLine = editor.cursor.line; drawer.close() }
    fun pause() { controlsJob?.cancel(); controlsKey = "" }
    fun resetControls() { pause(); controls.clearChildren() }

    fun toggleValue(position: Pos): Boolean {
        if (host().file == null || !editor.editable) return false
        val key = fields.keyAt(position.line) ?: return false
        val (start, end) = fields.valueSpan(position.line) ?: return false
        if (start == end || position.col < start || position.col > end) return false
        val value = doc.line(position.line).substring(start, end)
        val next = when (value.lowercase()) { "true" -> "false"; "false" -> "true"; else -> return false }
        if (!support().isBoolean(key, fields.sectionAt(position.line).orEmpty(), value)) return false
        fields.setValue(position.line, key, next)
        return true
    }

    private fun layoutCard(tipOn: Boolean, hasControls: Boolean, hasProblem: Boolean, candidates: Table? = null) {
        val key = "$tipOn|$hasControls|$hasProblem|${candidates != null}"
        if (key == cardRows) return
        cardRows = key
        fieldCard.clearChildren()
        fieldCard.margin(dp(4f), 0f, dp(4f), 0f)
        // 正在打字：抽屉里只放候选（说明和控件是给「已经写好的字段」用的，这时用不上）
        if (candidates != null) {
            fieldCard.add(candidates).growX().minWidth(0f).row()
            fieldCard.invalidateHierarchy()
            drawer.invalidate()
            return
        }
        if (tipOn) fieldCard.add(tip).growX().minWidth(0f).row()
        if (hasControls) fieldCard.add(controls).growX().pad(dp(4f), dp(16f), dp(12f), dp(16f)).row()
        if (hasProblem) fieldCard.add(problemLine).growX().minWidth(0f).left().pad(dp(2f), dp(16f), dp(12f), dp(16f)).row()
        if (!tipOn && !hasControls && !hasProblem) fieldCard.add(drawerEmpty).growX().pad(dp(14f), dp(16f), dp(14f), dp(16f)).row()
        fieldCard.invalidateHierarchy()
        drawer.invalidate()
    }

    /** 字段卡片：说明（IniAssist）+ 按字段类型给的控件 + 当前行的问题；三样都没有就收起。 */
    fun update(state: State) {
        val cl = state.line
        val line = cl + 1
        val tipOn = state.tipVisible
        val p = state.problems.filter { it.line == line && (it.severity == "error" || it.severity == "warn") }.maxByOrNull { if (it.severity == "error") 1 else 0 }
        Reactive.untracked { io.github.zenthxsin.rustwright.ui.kit.PerfTrace.span("controls") { updateControls(cl) } }
        // 最多两行左右，全文在问题列表里（点右上「错误 N」）
        problemLine.setText(p?.let { it.displayMessage }.orEmpty())
        problemLine.color.set(if (p?.severity == "error") EditorColors.ERROR else EditorColors.WARN)
        val hasControls = controls.hasChildren()
        val cand = state.candidates
        val show = cand != null || tipOn || p != null || hasControls
        // 没东西可看、抽屉正要收起：先别换内容，让它带着原来的内容滑回去（不然收起的那一下会闪成空白提示）
        val apply = { layoutCard(tipOn, hasControls, p != null, cand) }
        cardNow = apply
        if (show || !drawer.isShowing) apply()
        // 抽屉自己开合：有东西可看就弹出（候选 / 字段说明 / 控件 / 这一行的问题），没有就收起。
        // 手动收起过的那一行不再自动弹，除非开始打字出候选；光标换行后恢复自动。
        if (cl != dismissedLine) dismissedLine = -1
        Reactive.untracked {
            if (host().file == null) drawer.close()
            else if (!show) drawer.close(0.2f)
            else if (cand != null || dismissedLine < 0) drawer.open()
        }
        drawerHint.visible = show
        drawerHint.tint.set(if (p?.severity == "error") EditorColors.ERROR else if (p != null) EditorColors.WARN else K.subtle)
    }

    /**
     * 控件只在「字段 / 行 / 值」变了时重建；同一字段只是值变了（点开关、打字）等 220ms 再建，
     * 开关的滑块动画能走完，连续打字也不会每个字都重建一次。
     */
    private fun updateControls(cl: Int) {
        val f = host().file
        val key = if (f != null && support().configurationFields(f)) fields.keyAt(cl) else null
        val section = key?.let { fields.sectionAt(cl) }.orEmpty()
        if (key == null) {
            controlsJob?.cancel()
            if (controlsKey.isNotEmpty() || controls.hasChildren()) { controlsKey = ""; controls.clearChildren(); controls.getCell(controls)?.height(0f) }
            return
        }
        val span = fields.valueSpan(cl) ?: return
        val value = doc.line(cl).substring(span.first, span.second)
        val head = "$key|$section|$cl|"
        val next = head + value
        if (next == controlsKey) return
        val sameField = controlsKey.startsWith(head)
        controlsKey = next
        controlsJob?.cancel()
        val build = {
            controls.clearChildren()
            val ctx = EditorField(host(), key, value, section, cl,
                setValue = { v -> fields.setValue(cl, key, v) },
                setField = { k, v -> fields.setField(section, k, v) },
                newSection = { sec, ref -> fields.appendSection(sec); fields.setValue(cl, key, ref) },
                setValueLive = { v -> controlsKey = "$key|$section|$cl|$v"; fields.setValue(cl, key, v) })
            if (editor.editable) support().controls(ctx)?.let { controls.add(it).growX() }
            controls.invalidateHierarchy()
            fieldCard.invalidateHierarchy()
            io.github.zenthxsin.rustwright.ui.kit.PerfTrace.span("controls.layout") { fieldCard.validate() }
        }
        if (sameField && controls.hasChildren()) controlsJob = scope.launch(ArcDispatcher) { delay(220); if (controlsKey == next) { build(); refresh() } }
        else build()
    }

}
