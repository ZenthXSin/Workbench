package io.github.zenthxsin.rustwright.core.codex

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用真实 codex 可执行文件（环境变量 CODEX_BIN）+ 本地假 Responses 服务验证完整一轮：
 * 命令执行 → Rustwright 动态工具审批 → 工具结果 → 回复。没有 CODEX_BIN 时跳过。
 */
class CodexWorkbenchTest {

    private fun fakeResponses(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(200, 0); ex.responseBody.use { it.write("""{"data":[]}""".toByteArray()) }; return@createContext
            }
            val input = Json.parseToJsonElement(body).jsonObject["input"] as? JsonArray ?: JsonArray(emptyList())
            val outputs = input.count { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }
            ex.responseHeaders.add("content-type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out ->
                fun ev(type: String, data: String) { out.write("event: $type\ndata: ${data.dropLast(1)},\"type\":\"$type\"}\n\n".toByteArray()); out.flush() }
                ev("response.created", """{"response":{"id":"r$outputs"}}""")
                val item = when (outputs) {
                    0 -> """{"type":"function_call","id":"fc0","call_id":"c0","name":"exec_command","arguments":"{\"cmd\":\"echo hi > a.txt; cat a.txt\"}"}"""
                    1 -> """{"type":"function_call","id":"fc1","call_id":"c1","name":"rw_echo","arguments":"{\"text\":\"锈\"}"}"""
                    else -> """{"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":"完成"}]}"""
                }
                if (outputs >= 2) ev("response.output_text.delta", """{"item_id":"m1","output_index":0,"content_index":0,"delta":"完成"}""")
                ev("response.output_item.done", """{"output_index":0,"item":$item}""")
                ev("response.completed", """{"response":{"id":"r$outputs","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}""")
            }
        }
        server.start()
        return server
    }

    @Test
    fun fullTurnWithCommandToolApprovalAndReply() {
        val bin = System.getenv("CODEX_BIN")?.takeIf { File(it).canExecute() } ?: return
        val server = fakeResponses()
        val root = Files.createTempDirectory("codex").toFile()
        val projectDir = File(root, "proj").apply { mkdirs() }
        val executor = Executors.newSingleThreadExecutor()
        val main = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val launcher = CodexLauncher {
            ProcessBuilder(bin, "-c", "model_provider=\"newapi\"",
                "-c", "model_providers.newapi={name=\"New API\",base_url=\"http://127.0.0.1:${server.address.port}/v1\",env_key=\"NEW_API_KEY\",wire_api=\"responses\"}",
                "-c", "analytics.enabled=false", "-c", "check_for_update_on_startup=false", "app-server", "--listen", "stdio://")
                .also { it.environment()["NEW_API_KEY"] = "test"; it.environment()["CODEX_HOME"] = File(root, "home").apply { mkdirs() }.path }
                .redirectError(File(root, "err.log")).start()
        }
        var toolArg = ""
        val wb = CodexWorkbench(File(root, "state"), launcher, main, scope)
        wb.tools = { listOf(CodexTool("rw_echo", "Echo text.", buildJsonObject {
            put("type", "object"); put("properties", buildJsonObject { put("text", buildJsonObject { put("type", "string") }) })
        }, needsApproval = true) { call -> toolArg = call.arguments["text"]!!.jsonPrimitive.content; CodexToolResult(true, "echo:$toolArg") }) }

        try {
            val convId = runBlocking(main) {
                val p = wb.openProject("proj", projectDir.path)
                val c = wb.newConversation(p.id, CodexMode.CODE, model = "gpt-5.6-sol")
                wb.send(c.id, "做点事")
                c.id
            }
            val c = wb.conversation(convId)!!
            awaitOn(main) { c.approvals.isNotEmpty() || c.status == CodexStatus.ERROR }
            assertEquals(null, c.lastError, File(root, "err.log").readText().takeLast(2000))
            runBlocking(main) {
                val a = c.approvals.single()
                assertEquals(CodexApproval.Kind.TOOL, a.kind)
                wb.answer(a.id, CodexAnswer.Accept)
            }
            awaitOn(main) { c.status == CodexStatus.IDLE || c.status == CodexStatus.ERROR }
            runBlocking(main) {
                assertEquals(CodexStatus.IDLE, c.status, c.lastError)
                assertEquals("锈", toolArg)
                val items = c.turns.single().items
                assertTrue(items.any { it.type == "userMessage" && it.text == "做点事" })
                val cmd = items.first { it.type == "commandExecution" }
                assertTrue("hi" in cmd.output, cmd.output)
                assertTrue(items.any { it.type == "dynamicToolCall" && it.tool == "rw_echo" })
                assertEquals("完成", items.last { it.type == "agentMessage" }.text)
                assertEquals("做点事", c.title)
            }
            Thread.sleep(300)
            // 重新加载后历史仍在
            val again = CodexWorkbench(File(root, "state"), launcher, main, scope)
            assertEquals(1, again.conversations.size)
            assertTrue(again.conversations.single().turns.single().items.size >= 4)
        } finally {
            runBlocking(main) { wb.restart() }
            Thread.sleep(200)
            scope.cancel(); executor.shutdown(); server.stop(0)
        }
    }

    private fun awaitOn(main: kotlinx.coroutines.CoroutineDispatcher, timeout: Long = 60_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            if (runBlocking { withContext(main) { cond() } }) return
            Thread.sleep(100)
        }
        error("等待超时")
    }

    @Test
    fun parsesModelList() {
        val models = CodexWorkbench.parseModels(Json.parseToJsonElement(
            """{"data":[{"id":"gpt-x","displayName":"GPT-X","supportedReasoningEfforts":[{"reasoningEffort":"low"},{"reasoningEffort":"high"}],"defaultReasoningEffort":"low"}]}"""
        ))
        assertEquals(listOf(CodexModel("gpt-x", "GPT-X", listOf("low", "high"), "low")), models)
    }

}
