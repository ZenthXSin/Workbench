package io.github.zenthxsin.rustwright.core.agent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class ApprovalIsolationTest {
    @Test fun rememberedPermissionIsConversationScoped() = runBlocking {
        var prompts = 0
        val policy = ApprovalPolicy({ prompts++; ApprovalAnswer.ALLOW_SESSION })
        val tool = object : Tool(), PathAware {
            override val name = "audit_write"
            override val description = "fixture"
            override val parameters = buildJsonObject {}
            override val risk = ToolRisk.WRITE
            override fun touchedPaths(args: JsonObject) = listOf("units/a.ini")
            override suspend fun execute(userId: String, args: JsonObject) = ToolResult("unused")
        }
        assertEquals(ApprovalDecision.Allow, policy.approve(tool, buildJsonObject {}, "conversation-A"))
        assertEquals(ApprovalDecision.Allow, policy.approve(tool, buildJsonObject {}, "conversation-B"))
        assertEquals(2, prompts)
    }
}
