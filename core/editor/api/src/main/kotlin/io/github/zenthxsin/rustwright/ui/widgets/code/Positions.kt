package io.github.zenthxsin.rustwright.ui.widgets.code

/** 文档里的位置（行、列都从 0 开始，列按字符算）。 */
data class Pos(val line: Int, val col: Int) : Comparable<Pos> {
    override fun compareTo(other: Pos): Int = if (line != other.line) line.compareTo(other.line) else col.compareTo(other.col)
}

/**
 * 一次编辑：把 [start]..[oldEnd] 的文字 [removed] 换成 [inserted]，换完后插入段结束在 [newEnd]。
 * [origin] 区分来源（输入法、按键、程序、撤销），监听方可以据此决定要不要做联想 / 补全。
 */
data class Edit(val start: Pos, val oldEnd: Pos, val newEnd: Pos, val removed: String, val inserted: String, val origin: Origin) {
    enum class Origin { IME, KEY, PROGRAM, UNDO }
}
