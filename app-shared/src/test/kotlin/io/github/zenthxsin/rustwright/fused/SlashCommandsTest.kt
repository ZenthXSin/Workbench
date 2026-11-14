package io.github.zenthxsin.rustwright.fused

import kotlin.test.*

class SlashCommandsTest {
    @Test fun onlyTheElevenRequestedChineseCommandsAreOffered() {
        assertEquals(listOf("目标", "计划", "审查", "改动", "压缩", "总结", "权限", "技能", "工具", "插件", "初始化"), SlashCommands.suggestions("/").map { it.name })
        listOf("状态", "用量", "模型", "历史", "分支", "归档", "删除", "新对话", "重命名", "提及", "复制", "导出").forEach { assertNull(SlashCommands.parse("/$it")) }
    }
    @Test fun chineseAndEnglishAliasesPreserveTheTask() {
        assertEquals("goal", SlashCommands.parse(" /目标 完成坦克 并验证 ")?.command?.english)
        assertEquals("完成坦克 并验证", SlashCommands.parse(" /GOAL 完成坦克 并验证 ")?.argument)
        assertEquals("rw-tank 做一辆坦克", SlashCommands.parse("/技能\nrw-tank 做一辆坦克")?.argument)
    }
    @Test fun prefixFilteringAndAbsoluteFilePathsDoNotBecomeCommands() {
        assertEquals(listOf("压缩"), SlashCommands.suggestions("/comp").map { it.name })
        assertEquals(listOf("初始化"), SlashCommands.suggestions("/初").map { it.name })
        assertTrue(SlashCommands.suggestions("/技能 task").isEmpty())
        assertFalse(SlashCommands.isCommand("/workspace/mod/file.ini"))
        assertFalse(SlashCommands.isCommand("/sdcard 下的文件打不开"))
        assertFalse(SlashCommands.isCommand("/tmp 满了怎么办"))
        assertTrue(SlashCommands.isCommand("/目标 完成坦克"))
        assertTrue(SlashCommands.isCommand(" /diff"))
        assertNull(SlashCommands.parse("normal message"))
    }
}
