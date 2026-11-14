package io.github.zenthxsin.rustwright.core.agent

import io.github.zenthxsin.rustwright.core.storage.InMemorySecretStore
import io.github.zenthxsin.rustwright.core.storage.KeyValueStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreAgentTest {

    private class WriteTool(override val name: String = "write_file") : Tool(), PathAware {
        override val description = "write"
        override val parameters: JsonObject = buildJsonObject { put("type", "object") }
        override val risk = ToolRisk.WRITE
        override fun touchedPaths(args: JsonObject) = listOf(args["path"]!!.jsonPrimitive.content)
        override suspend fun execute(userId: String, args: JsonObject) = ToolResult("ok")
    }

    @Test
    fun approvalModes() = runTest {
        val prompts = mutableListOf<ApprovalPrompt>()
        var answer = ApprovalAnswer.ALLOW_SESSION
        val policy = ApprovalPolicy({ prompts += it; answer })
        val tool = WriteTool()
        suspend fun approve(path: String) = policy.approve(tool, buildJsonObject { put("path", path) }, "c")

        // 询问模式：首次询问并记住，同路径不再问
        assertEquals(ApprovalDecision.Allow, approve("units/tank.ini"))
        approve("units/tank.ini")
        assertEquals(1, prompts.size)
        // 元数据目录永远要问且不能记住
        approve(".git/config")
        assertFalse(prompts.last().canRemember)
        // 计划模式拒绝写
        policy.mode = ExecutionMode.PLAN
        assertTrue(approve("a.ini") is ApprovalDecision.Deny)
        // 自动模式：普通写入静默，危险路径仍要问，拒绝即拒绝
        policy.mode = ExecutionMode.AUTO
        val before = prompts.size
        approve("units/new.ini")
        assertEquals(before, prompts.size)
        answer = ApprovalAnswer.DENY
        assertTrue(approve(".project/state.json") is ApprovalDecision.Deny)
        assertTrue(approve(".workbench/state.json") is ApprovalDecision.Deny)
        assertEquals(before + 2, prompts.size)
    }

    @Test
    fun loadsSkillsFromDirectory() {
        val root = Files.createTempDirectory("skills").toFile()
        File(root, "rw-ini/SKILL.md").apply { parentFile.mkdirs() }.writeText("---\nname: rw-ini\ndescription: INI 结构化编辑\n---\n# 步骤\n先读后写")
        File(root, "advanced/group.md").apply { parentFile.mkdirs() }.writeText("高级技巧\n")
        File(root, "advanced/action/SKILL.md").apply { parentFile.mkdirs() }.writeText("---\ndescription: Action 写法\n---\nbody")
        File(root, "broken/SKILL.md").apply { parentFile.mkdirs() }.writeText("no frontmatter")
        val skills = Skills.fromDirectory(root)
        assertEquals(listOf("action", "rw-ini"), skills.map { it.name }.sorted())
        assertEquals("advanced", skills.first { it.name == "action" }.group)
        Skills.install(skills)
        assertEquals("# 步骤\n先读后写", Skills.all.first { it.name == "rw-ini" }.body)
    }

    @Test
    fun serviceKeepsSettingsAndToken() {
        val dir = Files.createTempDirectory("agent").toFile()
        val store = KeyValueStore(File(dir, "settings.json"))
        val service = AgentService(store, InMemorySecretStore(), AgentToolRegistry(), prompter = { ApprovalAnswer.ALLOW_ONCE }, interaction = null)
        assertFalse(service.ready)
        service.token = "sk-test"
        service.updateSettings { it.copy(chatModel = "gpt-6-astra", mode = ExecutionMode.AUTO) }
        assertTrue(service.ready)
        assertEquals(ExecutionMode.AUTO, service.approval.mode)
        assertEquals("gpt-6-astra", service.channel?.chatModel)
        val reloaded = AgentService(store, InMemorySecretStore(), AgentToolRegistry(), prompter = { ApprovalAnswer.ALLOW_ONCE }, interaction = null)
        assertEquals("gpt-6-astra", reloaded.settings.chatModel)
    }

    @Test
    fun registryFiltersByScene() {
        val registry = AgentToolRegistry()
        registry.register(WriteTool("write_file"), AgentScene("editor"))
        registry.register(WriteTool("generate_image"), AgentScene("image"))
        registry.register(WriteTool("note"))
        assertEquals(listOf("write_file", "note"), registry.toolsFor(AgentScene("editor")).map { it.name })
    }

    @Test
    fun registryGroupsByToolset() {
        val registry = AgentToolRegistry()
        registry.register(WriteTool("generate_image"), AgentScene("image"), toolset = "sample-image")
        registry.register(WriteTool("edit_map"), toolset = "sample-layout")
        registry.register(WriteTool("info"))
        assertEquals(mapOf("sample-image" to listOf("generate_image"), "sample-layout" to listOf("edit_map"), Toolsets.CORE to listOf("info")),
            registry.byToolset().mapValues { (_, v) -> v.map { it.name } })
        assertEquals("sample-layout", registry.toolsetOf("edit_map"))
    }
}
