package io.github.zenthxsin.rustwright.ui.editor
import kotlin.test.*
import java.io.File
class EditorSupportTest {
    @Test fun outlinesCodeMarkdownAndConfigWithoutGameKnowledge() {
        assertEquals(listOf("fun hello"), TextOutline.parse(listOf("fun hello() {}"), false).map { it.name })
        assertEquals(listOf("# Notes"), TextOutline.parse(listOf("# Notes", "plain"), false).map { it.name })
        val nodes = TextOutline.parse(listOf("[settings]", "enabled=true", "name: demo"), true)
        assertEquals("[settings]", nodes.single().name); assertEquals(listOf("enabled", "name"), nodes.single().children.map { it.name })
        assertEquals(1, nodes.single().children.first().line)
    }
    @Test fun highlighterPropagatesAndClosesBlockCommentState() {
        val out = ByteArray(80)
        assertEquals(1, GenericCodeHighlighter.highlight("/* begin", 0, out)); assertEquals(21, out[0].toInt())
        assertEquals(0, GenericCodeHighlighter.highlight("end */ val x = 42", 1, out))
        assertEquals(22, out[7].toInt()); assertEquals(23, out[15].toInt())
    }
    @Test fun formatterPreservesBlockContentsAndSeparators() {
        val support = BuiltinEditorSupport()
        assertEquals("[settings]\nkey= value\n", support.format(File("settings.ini"), "[settings]\nkey= value   \n"))
        assertEquals("const s = 'a:b';\n", support.format(File("code.js"), "const s = 'a:b';  \n"))
    }
    @Test fun sectionInsertionNeverSplitsTheCurrentSection() {
        val result = EditorSymbolDrawer.sectionInsertion(listOf("[one]", "a: 1", "b: 2", "", "[two]"), 1, "[three]")
        assertEquals(2, result.first); assertEquals("\n\n[three]\n", result.second)
    }
}
