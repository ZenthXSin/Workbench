package io.github.zenthxsin.rustwright.core.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpBridgeTest {
    private val schema = buildJsonObject { put("type", "object") }
    private val calls = mutableListOf<Triple<String, String?, String?>>()
    private val pic = CodexToolset("rustwright-pic", "RustPic 生图", "AI 生图", listOf(
        CodexTool("generate_image", "gen", schema, needsApproval = true) { CodexToolResult(true, "unused") },
        CodexTool("list_images", "list", schema, needsApproval = false) { CodexToolResult(true, "unused") },
    ))
    private val core = CodexToolset("rustwright-core", "本体基础", "app", listOf(CodexTool("app_status", "s", schema, false) { CodexToolResult(true, "") }))
    private val bridge = McpBridge({ listOf(core, pic) }) { set, tool, _, thread, turn ->
        calls += Triple("${set.id}/${tool.name}", thread, turn)
        CodexToolResult(tool.name != "generate_image", "ran ${tool.name}")
    }

    @AfterTest fun stop() = bridge.stop()

    private fun post(path: String, body: JsonObject, token: String? = bridge.token): Pair<Int, JsonObject?> {
        val c = URI("http://127.0.0.1:${bridge.port}$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = runCatching { c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrDefault("")
        return code to text.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject }
    }

    private fun rpc(id: Int, method: String, params: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
    }

    @Test
    fun servesEachToolsetSeparately() {
        bridge.start()
        val (code, init) = post("/mcp/rustwright-pic", rpc(0, "initialize", buildJsonObject { put("protocolVersion", "2025-06-18") }))
        assertEquals(200, code)
        assertEquals("rustwright-pic", init!!["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        // 通知没有回复体
        assertEquals(202, post("/mcp/rustwright-pic", buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }).first)

        val tools = post("/mcp/rustwright-pic", rpc(1, "tools/list")).second!!["result"]!!.jsonObject["tools"] as JsonArray
        assertEquals(listOf("generate_image", "list_images"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        // 不用确认的工具标成只读，Codex 就不会再问
        assertEquals(listOf("false", "true"), tools.map { it.jsonObject["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.content })
        val coreTools = post("/mcp/rustwright-core", rpc(1, "tools/list")).second!!["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("app_status"), coreTools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun callCarriesCodexThreadAndReportsErrors() {
        bridge.start()
        val params = buildJsonObject {
            put("name", "generate_image")
            putJsonObject("arguments") { put("prompt", "tank") }
            putJsonObject("_meta") { putJsonObject("x-codex-turn-metadata") { put("thread_id", "t1"); put("turn_id", "u1") } }
        }
        val r = post("/mcp/rustwright-pic", rpc(2, "tools/call", params)).second!!["result"]!!.jsonObject
        assertEquals("true", r["isError"]!!.jsonPrimitive.content)
        assertEquals("ran generate_image", r["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(listOf(Triple<String, String?, String?>("rustwright-pic/generate_image", "t1", "u1")), calls)
        // 别的组的工具在这个地址上不存在
        val wrong = post("/mcp/rustwright-pic", rpc(3, "tools/call", buildJsonObject { put("name", "app_status") })).second!!["result"]!!.jsonObject
        assertEquals("true", wrong["isError"]!!.jsonPrimitive.content)
        assertEquals(1, calls.size)
    }

    @Test
    fun rejectsMissingTokenAndUnknownToolset() {
        bridge.start()
        assertEquals(401, post("/mcp/rustwright-pic", rpc(0, "tools/list"), token = null).first)
        assertEquals(401, post("/mcp/rustwright-pic", rpc(0, "tools/list"), token = "nope").first)
        assertEquals(404, post("/mcp/other", rpc(0, "tools/list")).first)
        assertTrue(calls.isEmpty())
    }
}
