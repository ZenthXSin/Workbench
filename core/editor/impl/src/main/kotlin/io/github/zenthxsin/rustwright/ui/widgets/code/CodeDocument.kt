package io.github.zenthxsin.rustwright.ui.widgets.code

import io.github.zenthxsin.rustwright.core.editor.api.TextDocument

/**
 * 代码编辑器的文档：按行存（每行一个 String），改一处只动受影响的几行，和文件大小无关。
 * 整篇文字（[text]）按版本缓存，只在需要时才拼。自带撤销 / 重做：记的是编辑而不是快照，
 * 连续打字（同一行、间隔 < 700ms、没有换行）合并成一步。
 */
class CodeDocument(text: String = "") : TextDocument() {
    private val lines = ArrayList<String>()
    /** 每次修改 +1，监听方用它判断有没有变（不用比较整篇文字）。 */
    override var version = 0; private set
    private var cachedText: String? = null
    private var cachedTextVersion = -1
    /** 各行起点在整篇文字里的下标，按需重建。 */
    private var starts = IntArray(0)
    private var startsVersion = -1

    private val listeners = ArrayList<(Edit) -> Unit>()
    private val undoStack = ArrayList<Step>()
    private val redoStack = ArrayList<Step>()
    private var lastEditAt = 0L
    /** 把 [undoStack] 顶上那一步当作已关闭（下一次编辑另起一步）。 */
    private var sealed = true

    private class Step(val edits: ArrayList<Edit>, val cursorBefore: Pos, var cursorAfter: Pos)

    init { setText(text) }

    override val lineCount: Int get() = lines.size
    override fun line(i: Int): String = lines[i]
    override val canUndo get() = undoStack.isNotEmpty()
    override val canRedo get() = redoStack.isNotEmpty()

    /** 所有行的浅拷贝（给后台线程拼整篇文字用，拷的是引用，很快）。 */
    override fun linesSnapshot(): List<String> = ArrayList(lines)

    override fun addListener(l: (Edit) -> Unit) { listeners += l }
    override fun removeListener(listener: (Edit) -> Unit) { listeners -= listener }

    /** 整篇文字（按版本缓存）。 */
    override val text: String get() {
        if (cachedTextVersion != version) { cachedText = lines.joinToString("\n"); cachedTextVersion = version }
        return cachedText!!
    }

    /** 整体换文字（打开文件）：清空撤销栈，不通知监听方。 */
    override fun setText(text: String) {
        lines.clear()
        var s = 0
        while (true) {
            val e = text.indexOf('\n', s)
            if (e < 0) { lines += text.substring(s).removeSuffix("\r"); break }
            lines += text.substring(s, e).removeSuffix("\r")
            s = e + 1
        }
        undoStack.clear(); redoStack.clear(); sealed = true
        version++
    }

    override fun clamp(p: Pos): Pos {
        val l = p.line.coerceIn(0, lines.size - 1)
        return Pos(l, p.col.coerceIn(0, lines[l].length))
    }

    override fun end(): Pos = Pos(lines.size - 1, lines.last().length)

    override fun offsetOf(p: Pos): Int {
        ensureStarts()
        val c = clamp(p)
        return starts[c.line] + c.col
    }

    override fun posOf(offset: Int): Pos {
        ensureStarts()
        val o = offset.coerceIn(0, starts[starts.size - 1] + lines.last().length)
        var lo = 0; var hi = starts.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (starts[mid] <= o) lo = mid else hi = mid - 1 }
        return Pos(lo, o - starts[lo])
    }

    private fun ensureStarts() {
        if (startsVersion == version) return
        if (starts.size != lines.size) starts = IntArray(lines.size)
        var acc = 0
        for (i in lines.indices) { starts[i] = acc; acc += lines[i].length + 1 }
        startsVersion = version
    }

    override fun textIn(a: Pos, b: Pos): String {
        val s = clamp(minOf(a, b)); val e = clamp(maxOf(a, b))
        if (s.line == e.line) return lines[s.line].substring(s.col, e.col)
        val sb = StringBuilder(lines[s.line].substring(s.col))
        for (i in s.line + 1 until e.line) sb.append('\n').append(lines[i])
        sb.append('\n').append(lines[e.line], 0, e.col)
        return sb.toString()
    }

    /**
     * 把 [a]..[b] 换成 [text]，返回插入段的结束位置。[cursorBefore] 记进撤销栈（撤销后光标回到那里）。
     */
    override fun replace(a: Pos, b: Pos, text: String, origin: Edit.Origin, cursorBefore: Pos): Pos {
        val s = clamp(minOf(a, b)); val e = clamp(maxOf(a, b))
        val clean = if (text.indexOf('\r') >= 0) text.replace("\r\n", "\n").replace('\r', '\n') else text
        if (s == e && clean.isEmpty()) return s
        val removed = textIn(s, e)
        val newEnd = apply(s, e, clean)
        val edit = Edit(s, e, newEnd, removed, clean, origin)
        if (origin != Edit.Origin.UNDO) record(edit, cursorBefore, newEnd)
        version++
        listeners.forEach { it(edit) }
        return newEnd
    }

    private fun apply(s: Pos, e: Pos, text: String): Pos {
        val head = lines[s.line].substring(0, s.col)
        val tail = lines[e.line].substring(e.col)
        // 删掉 s.line+1..e.line
        if (e.line > s.line) lines.subList(s.line + 1, e.line + 1).clear()
        if (text.indexOf('\n') < 0) {
            lines[s.line] = head + text + tail
            return Pos(s.line, s.col + text.length)
        }
        val parts = text.split('\n')
        lines[s.line] = head + parts[0]
        val add = ArrayList<String>(parts.size - 1)
        for (i in 1 until parts.size - 1) add += parts[i]
        add += parts.last() + tail
        lines.addAll(s.line + 1, add)
        return Pos(s.line + parts.size - 1, parts.last().length)
    }

    private fun record(edit: Edit, cursorBefore: Pos, cursorAfter: Pos) {
        redoStack.clear()
        val now = System.currentTimeMillis()
        val top = undoStack.lastOrNull()
        val last = top?.edits?.last()
        val typing = edit.removed.isEmpty() && edit.inserted.isNotEmpty() && '\n' !in edit.inserted
        val deleting = edit.inserted.isEmpty() && '\n' !in edit.removed
        val mergeable = !sealed && top != null && last != null && now - lastEditAt < 700 && edit.start.line == last.start.line &&
            ((typing && last.removed.isEmpty() && last.newEnd == edit.start) || (deleting && last.inserted.isEmpty() && edit.oldEnd == last.start))
        if (mergeable) { top!!.edits += edit; top.cursorAfter = cursorAfter }
        else {
            undoStack += Step(arrayListOf(edit), cursorBefore, cursorAfter)
            if (undoStack.size > 500) undoStack.removeAt(0)
        }
        sealed = false
        lastEditAt = now
    }

    /** 下一次编辑另起一步（光标被点到别处、接受补全之后等）。 */
    override fun seal() { sealed = true }

    /** 撤销一步，返回撤销后光标该在的位置。 */
    override fun undo(): Pos? {
        val step = undoStack.removeLastOrNull() ?: return null
        for (ed in step.edits.asReversed()) replace(ed.start, ed.newEnd, ed.removed, Edit.Origin.UNDO)
        redoStack += step
        sealed = true
        return clamp(step.cursorBefore)
    }

    override fun redo(): Pos? {
        val step = redoStack.removeLastOrNull() ?: return null
        for (ed in step.edits) replace(ed.start, ed.oldEnd, ed.inserted, Edit.Origin.UNDO)
        undoStack += step
        sealed = true
        return clamp(step.cursorAfter)
    }
}
