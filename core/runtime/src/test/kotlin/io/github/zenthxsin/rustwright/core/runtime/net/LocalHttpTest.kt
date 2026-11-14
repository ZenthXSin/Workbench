package io.github.zenthxsin.rustwright.core.runtime.net

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalHttpTest {
    /** 发一段原始请求，返回服务端读到的结果（或拒绝的状态码）。 */
    private fun roundTrip(raw: String): Any {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            var result: Any = "none"
            val t = thread {
                server.accept().use { s ->
                    result = try { LocalHttp.read(s, "tok") } catch (e: LocalHttp.Rejected) { e.status }
                }
            }
            Socket("127.0.0.1", server.localPort).use { it.getOutputStream().write(raw.toByteArray()); t.join(5000) }
            return result
        }
    }

    @Test
    fun acceptsDuplicateAcceptHeaders() {
        // Codex 的客户端会带默认 accept: */* 再加一个 MCP 要求的 Accept
        val body = """{"jsonrpc":"2.0","id":1,"method":"initialize"}"""
        val r = roundTrip("POST /mcp/x HTTP/1.1\r\nhost: 127.0.0.1\r\naccept: */*\r\naccept: application/json, text/event-stream\r\n" +
            "authorization: Bearer tok\r\ncontent-type: application/json\r\ncontent-length: ${body.length}\r\n\r\n$body")
        assertEquals(body, (r as LocalHttp.Request).body.toString(Charsets.UTF_8))
    }

    @Test
    fun rejectsAmbiguousDuplicates() {
        assertEquals(400, roundTrip("POST /mcp/x HTTP/1.1\r\nauthorization: Bearer tok\r\ncontent-length: 2\r\ncontent-length: 3\r\n\r\n{}"))
        assertEquals(400, roundTrip("POST /mcp/x HTTP/1.1\r\nauthorization: Bearer tok\r\nauthorization: Bearer tok\r\n\r\n"))
        assertEquals(401, roundTrip("POST /mcp/x HTTP/1.1\r\nauthorization: Bearer nope\r\n\r\n"))
    }
}
