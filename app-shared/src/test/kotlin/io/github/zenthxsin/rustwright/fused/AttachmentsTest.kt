package io.github.zenthxsin.rustwright.fused

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentsTest {
    @Test
    fun composeAndSplitRoundTrip() {
        val msg = Attachments.compose("看看这个坦克", listOf("tmp/20261030-101010-tank.png", "tmp/20261030-101011-a.ini"))
        assertEquals("看看这个坦克\n\n[附件] tmp/20261030-101010-tank.png\n[附件] tmp/20261030-101011-a.ini", msg)
        assertEquals("看看这个坦克" to listOf("tmp/20261030-101010-tank.png", "tmp/20261030-101011-a.ini"), Attachments.split(msg))
        assertEquals("" to listOf("tmp/x.png"), Attachments.split(Attachments.compose("", listOf("tmp/x.png"))))
        // 只认末尾的附件行，正文中间提到的不算
        assertEquals("[附件] 不是附件\n正文" to emptyList(), Attachments.split("[附件] 不是附件\n正文"))
    }

    @Test
    fun copyInKeepsBothSameNamedFiles() {
        val dir = Files.createTempDirectory("att").toFile()
        val src = Files.createTempFile("x", ".png").toFile().apply { writeText("png") }
        val a = Attachments.copyIn(dir, src)
        val b = Attachments.copyIn(dir, src)
        assertTrue(a.startsWith("tmp/") && b.startsWith("tmp/") && a != b)
        assertEquals("png", dir.resolve(b).readText())
        dir.deleteRecursively(); src.delete()
    }
}
