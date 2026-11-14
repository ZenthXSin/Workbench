package io.github.zenthxsin.rustwright.core.codex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import io.github.zenthxsin.rustwright.core.runtime.net.BoundedSocketPool
import io.github.zenthxsin.rustwright.core.runtime.net.LocalHttp

/**
 * 一组交给助手的工具：在 Codex 里是一个独立的 MCP 服务器，名字就是 [id]。
 * [tools] 里 [CodexTool.needsApproval] 为 false 的标成只读，Codex 直接调用；其余由 Codex 先请用户确认。
 */
class CodexToolset(val id: String, val title: String, val description: String, val tools: List<CodexTool>)

/**
 * App 内的 MCP 服务（Streamable HTTP，只回 JSON，不开 SSE 流）：每组工具一个地址 `/mcp/<组 id>`。
 * 只监听 127.0.0.1，请求必须带 `Authorization: Bearer <token>`；虚拟环境（proot）和 App 共用网络，Codex 直接连。
 *
 * Codex 的每次 tools/call 都在 `_meta["x-codex-turn-metadata"]` 里带上 thread_id / turn_id，
 * [call] 据此找到发起调用的会话和项目。
 */
class McpBridge(
    private val toolsets: () -> List<CodexToolset>,
    private val call: suspend (toolset: CodexToolset, tool: CodexTool, args: JsonObject, threadId: String?, turnId: String?) -> CodexToolResult,
) {
    /** 每次启动随机生成，经环境变量交给 Codex。 */
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Volatile private var server: ServerSocket? = null
    private var pool = BoundedSocketPool("rw-mcp")

    /** 已监听的端口；没启动为 0。 */
    val port: Int get() = server?.localPort ?: 0

    fun url(toolset: String) = "http://127.0.0.1:$port/mcp/$toolset"

    /** 启动监听（已在监听则直接返回端口）。 */
    @Synchronized
    fun start(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        pool = BoundedSocketPool("rw-mcp")
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            while (!s.isClosed) {
                val socket = try { s.accept() } catch (_: SocketException) { break }
                pool.execute(socket, ::serve)
            }
        }, "rw-mcp-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        pool.close()
    }

    private fun serve(socket: Socket) = socket.use { sock ->
        runCatching {
            val request = try { LocalHttp.read(sock, token) }
                catch (e: LocalHttp.Rejected) { LocalHttp.reject(sock, e.status); return@runCatching }
            val (status, response) = handle(request.method, request.target, "Bearer $token", request.body)
            val bytes = response?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val head = buildString {
                append("HTTP/1.1 ").append(status).append(' ').append(REASONS[status] ?: "OK").append("\r\n")
                if (response != null) append("Content-Type: application/json\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            sock.getOutputStream().apply { write(head.toByteArray(Charsets.UTF_8)); write(bytes); flush() }
        }
    }

    /** 处理一次 HTTP 请求，返回状态码和 JSON 体（没有体时为 null）。 */
    internal fun handle(method: String, path: String, authorization: String?, body: ByteArray): Pair<Int, JsonElement?> {
        if (authorization != "Bearer $token") return 401 to null
        val id = path.removePrefix("/mcp/").substringBefore('?').trimEnd('/')
        if (!path.startsWith("/mcp/")) return 404 to null
        // 只回 JSON，不提供服务端推送流和会话
        if (method == "GET") return 405 to null
        if (method == "DELETE") return 200 to null
        if (method != "POST") return 405 to null
        val set = toolsets().firstOrNull { it.id == id } ?: return 404 to null
        val msg = runCatching { Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as JsonObject }.getOrNull()
            ?: return 400 to rpcError(JsonNull, -32700, "Parse error")
        val rpcId = msg["id"] ?: return 202 to null
        val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
        val result = when (msg.text("method")) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", params.text("protocolVersion") ?: PROTOCOL)
                putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
                putJsonObject("serverInfo") { put("name", set.id); put("title", set.title); put("version", "1") }
                put("instructions", set.description)
            }
            "ping" -> JsonObject(emptyMap())
            "tools/list" -> buildJsonObject {
                putJsonArray("tools") {
                    set.tools.forEach { t ->
                        addJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("inputSchema", t.inputSchema)
                            putJsonObject("annotations") { put("readOnlyHint", !t.needsApproval) }
                        }
                    }
                }
            }
            "tools/call" -> {
                val name = params.text("name")
                val tool = set.tools.firstOrNull { it.name == name }
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val turn = (params["_meta"] as? JsonObject)?.get("x-codex-turn-metadata") as? JsonObject
                val r = if (tool == null) CodexToolResult(false, "未知工具：$name")
                else runCatching { runBlocking(Dispatchers.IO) { call(set, tool, args, turn?.text("thread_id"), turn?.text("turn_id")) } }
                    .getOrElse { CodexToolResult(false, "工具执行失败：${it.message}") }
                buildJsonObject {
                    putJsonArray("content") { addJsonObject { put("type", "text"); put("text", r.text) } }
                    put("isError", !r.success)
                }
            }
            else -> return 200 to rpcError(rpcId, -32601, "Method not found")
        }
        return 200 to buildJsonObject { put("jsonrpc", "2.0"); put("id", rpcId); put("result", result) }
    }

    private fun rpcError(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        putJsonObject("error") { put("code", code); put("message", message) }
    }

    private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16_384) return null
        }
    }

    companion object {
        const val PROTOCOL = "2025-06-18"
        /** Codex 读令牌用的环境变量名。 */
        const val TOKEN_ENV = "RUSTWRIGHT_MCP_TOKEN"
        private val REASONS = mapOf(200 to "OK", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 405 to "Method Not Allowed")
    }
}
