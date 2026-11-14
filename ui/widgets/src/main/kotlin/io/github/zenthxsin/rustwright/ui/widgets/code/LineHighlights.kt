package io.github.zenthxsin.rustwright.ui.widgets.code

/** 按行着色：每个字符一个种类字节；[state] 是跨行状态（如是否在多行字符串里），返回行尾状态。 */
interface LineHighlighter {
    fun highlight(line: String, state: Int, out: ByteArray): Int
}

/**
 * 增量着色缓存：每行记行首状态、行尾状态和种类数组。改动只清掉被改的行；
 * 取某行时从已确认处往下走，行首状态没变的行直接复用，只有状态变了（如打开 / 关上三引号）的行才重算。
 */
class LineHighlights(private val doc: io.github.zenthxsin.rustwright.core.editor.api.TextDocument, private val hl: LineHighlighter) {
    private val kinds = ArrayList<ByteArray?>()
    private val startState = ArrayList<Int>()
    private val endState = ArrayList<Int>()
    /** [0, validUpTo) 的行首状态都已确认（之后的行缓存可能还能用，要核对行首状态）。 */
    private var validUpTo = 0

    init {
        reset()
        doc.addListener { onEdit(it) }
    }

    fun reset() {
        kinds.clear(); startState.clear(); endState.clear()
        repeat(doc.lineCount) { kinds += null; startState += 0; endState += 0 }
        validUpTo = 0
    }

    private fun onEdit(e: Edit) {
        val removedLines = e.oldEnd.line - e.start.line
        val addedLines = e.newEnd.line - e.start.line
        // 把 start..oldEnd 换成 start..newEnd，改过的行清掉缓存
        repeat(removedLines) { kinds.removeAt(e.start.line + 1); startState.removeAt(e.start.line + 1); endState.removeAt(e.start.line + 1) }
        repeat(addedLines) { kinds.add(e.start.line + 1, null); startState.add(e.start.line + 1, -1); endState.add(e.start.line + 1, 0) }
        for (l in e.start.line..e.newEnd.line) kinds[l] = null
        // 往下的行内容没变，行首状态对得上就直接复用（见 kindsOf）
        validUpTo = minOf(validUpTo, e.start.line)
    }

    private fun compute(l: Int, state: Int) {
        val line = doc.line(l)
        val arr = kinds[l]?.takeIf { it.size >= line.length } ?: ByteArray(maxOf(line.length, 8))
        startState[l] = state
        endState[l] = hl.highlight(line, state, arr)
        kinds[l] = arr
    }

    /** 第 [l] 行的种类数组（长度 ≥ 行长）；需要时先把上面没算过的行算完。 */
    fun kindsOf(l: Int): ByteArray {
        while (validUpTo <= l) {
            val s = if (validUpTo == 0) 0 else endState[validUpTo - 1]
            if (kinds[validUpTo] == null || startState[validUpTo] != s) compute(validUpTo, s)
            validUpTo++
        }
        return kinds[l] ?: run { compute(l, startState[l]); kinds[l]!! }
    }
}

/** INI 按行着色（种类见 [io.github.zenthxsin.rustwright.ui.widgets.IniHighlighter]）；状态 1 = 在三引号块里。 */
object IniLineHighlighter : LineHighlighter {
    override fun highlight(line: String, state: Int, out: ByteArray): Int =
        if (io.github.zenthxsin.rustwright.ui.widgets.IniHighlighter.classifyLine(line, state == 1, out)) 1 else 0
}
