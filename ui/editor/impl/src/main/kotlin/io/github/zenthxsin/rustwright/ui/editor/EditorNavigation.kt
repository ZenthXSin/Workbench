package io.github.zenthxsin.rustwright.ui.editor
import io.github.zenthxsin.rustwright.ui.widgets.code.CodeEditor
import io.github.zenthxsin.rustwright.ui.widgets.code.Pos
fun CodeEditor.jumpTo(line: Int, column: Int) {
    val l = (line - 1).coerceIn(0, doc.lineCount - 1)
    setCursor(Pos(l, (column - 1).coerceIn(0, doc.line(l).length)))
    revealCursor(); focusAndShowKeyboard()
}
