package io.github.zenthxsin.rustwright.core.terminal

import com.termux.terminal.KeyCodes
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmulatorTest {
    private class Sink : TerminalOutput() {
        val written = StringBuilder()
        override fun write(data: ByteArray, offset: Int, count: Int) { written.append(String(data, offset, count)) }
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    @Test
    fun parsesTextColorsAndCursor() {
        val sink = Sink()
        val emu = TerminalEmulator(sink, 20, 5, 1, 1, 100, null)
        val bytes = "hello\r\n\u001b[31m红色\u001b[0m!".toByteArray()
        emu.append(bytes, bytes.size)
        val screen = emu.screen
        assertTrue(screen.transcriptText.startsWith("hello\n红色!"), screen.transcriptText)
        assertEquals(1, emu.cursorRow)
        assertEquals(5, emu.cursorCol) // 两个全角字符各占 2 列
        assertEquals(1, TextStyle.decodeForeColor(screen.getStyleAt(1, 0)))
        // 设备状态查询会回写
        val dsr = "\u001b[6n".toByteArray()
        emu.append(dsr, dsr.size)
        assertEquals("\u001b[2;6R", sink.written.toString())
        assertEquals("\u001b[A", KeyHandler.getCode(KeyCodes.KEYCODE_DPAD_UP, 0, false, false))
    }

    @Test
    fun wideWrapCopiesOnlyRealSpacesAndSurvivesResize() {
        val cases = listOf("1234中文", "123 中文", "1234 中文", "  中文内容 ".repeat(12).trimEnd())
        for (columns in 5..11) for (text in cases) {
            val emu = TerminalEmulator(Sink(), columns, 8, 1, 1, 2000, null)
            val bytes = (text + "\r\nEND").toByteArray()
            emu.append(bytes, bytes.size)
            fun snapshot() = emu.screen.getSelectedText(0, -emu.screen.activeTranscriptRows, emu.mColumns - 1, emu.mRows - 1).trimEnd('\n')
            assertEquals("$text\nEND", snapshot(), "columns=$columns")
            for (width in listOf(9, 4, 12, 5, 11)) {
                emu.resize(width, 8, 1, 1)
                assertEquals("$text\nEND", snapshot(), "columns=$columns resized=$width")
            }
        }
    }

    @Test
    fun replacingWideWrapPaddingAndRecyclingRowsKeepsRealSpaces() {
        val emu = TerminalEmulator(Sink(), 5, 3, 1, 1, 2000, null)
        fun emit(text: String) = text.toByteArray().let { emu.append(it, it.size) }
        emit("1234中")
        // A real space written into the old display-only cell must remain in a joined copy.
        emit("\u001b[1;5H ")
        assertEquals("1234 中", emu.screen.transcriptText)
        emit("\u001b[2J\u001b[H1\r\n2\r\n3\r\n4\r\n5")
        assertTrue(emu.screen.transcriptText.endsWith("1\n2\n3\n4\n5"), emu.screen.transcriptText)
    }
}
