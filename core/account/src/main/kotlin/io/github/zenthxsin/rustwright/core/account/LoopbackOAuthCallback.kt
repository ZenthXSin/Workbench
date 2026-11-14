package io.github.zenthxsin.rustwright.core.account

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class LoopbackOAuthCallback(private val redirect: String, private val returnUri: String? = null) : java.io.Closeable {
    private val uri = URI(redirect)
    private val server = ServerSocket().apply {
        reuseAddress = false
        try { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), uri.port), 4) }
        catch (e: Exception) { close(); throw IllegalStateException("本地登录端口被占用，请结束另一次登录后重试", e) }
        soTimeout = 250
    }

    suspend fun awaitCode(state: String, issuer: String): String = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + 180_000_000_000L
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                it.soTimeout = 1000
                val result = runCatching {
                    val input = it.getInputStream()
                    fun line(): String {
                        val bytes = java.io.ByteArrayOutputStream()
                        while (bytes.size() < 8192) {
                            val b = input.read(); check(b >= 0)
                            if (b == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                            bytes.write(b)
                        }
                        error("回调请求过长")
                    }
                    val first = line().split(' ')
                    check(first.size == 3 && first[0] == "GET")
                    var host: String? = null
                    var total = 0
                    while (true) {
                        val header = line(); if (header.isEmpty()) break
                        total += header.length; check(total <= 16384)
                        if (header.substringBefore(':').equals("host", true)) {
                            check(host == null); host = header.substringAfter(':').trim()
                        }
                    }
                    check(host == uri.rawAuthority)
                    parse(first[1], state, issuer)
                }
                val html = result.isSuccess && returnUri != null
                val body = when {
                    html -> successPage(returnUri!!)
                    result.isSuccess -> "已收到授权结果，请返回 Workbench。"
                    else -> "此请求不是有效的 Workbench 登录回调。"
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                runCatching {
                    it.getOutputStream().apply {
                        write(("HTTP/1.1 ${if (result.isSuccess) "200 OK" else "400 Bad Request"}\r\n" +
                            "Content-Type: ${if (html) "text/html" else "text/plain"}; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        write(bytes); flush()
                    }
                }
                result.getOrNull()?.let { params ->
                    if (params.containsKey("error")) error("授权被取消或拒绝，请重新登录")
                    return@withContext params.getValue("code")
                }
            }
        }
        error("登录等待超时，请重新登录")
    }

    /** 登录成功页：立刻跳回软件，跳不回去（浏览器拦了）时给一个按钮。 */
    private fun successPage(back: String): String {
        val href = back.replace("&", "&amp;").replace("\"", "&quot;")
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Workbench</title><meta http-equiv="refresh" content="0;url=$href"></head>
<body style="margin:0;background:#141413;color:#e6e6e6;font:16px/1.6 system-ui,sans-serif;text-align:center;padding:30vh 24px 0">
<p>登录成功，正在返回 Workbench…</p>
<p><a href="$href" style="display:inline-block;margin-top:12px;padding:10px 22px;border-radius:12px;background:#f2f1ed;color:#141413;text-decoration:none">返回 Workbench</a></p>
<script>location.replace("$href")</script></body></html>"""
    }

    internal fun parse(target: String, state: String, issuer: String): Map<String, String> {
        check(target.startsWith('/') && !target.startsWith("//"))
        val request = URI(target)
        check(request.rawPath == uri.rawPath && request.fragment == null)
        val pairs = request.rawQuery.orEmpty().split('&').map {
            val split = it.split('=', limit = 2); check(split.size == 2)
            URLDecoder.decode(split[0], "UTF-8") to URLDecoder.decode(split[1], "UTF-8")
        }
        check(pairs.map { it.first }.distinct().size == pairs.size)
        val params = pairs.toMap()
        check(params["state"] == state && params["iss"] == issuer)
        check(params.containsKey("code") != params.containsKey("error"))
        check((params["code"] ?: params["error"]).orEmpty().isNotBlank())
        return params
    }

    override fun close() = server.close()
}
