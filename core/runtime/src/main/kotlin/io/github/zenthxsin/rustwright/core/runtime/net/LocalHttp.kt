package io.github.zenthxsin.rustwright.core.runtime.net

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Small authenticated loopback protocol. Bounds apply before any body allocation. */
object LocalHttp {
    class Rejected(val status: Int) : Exception()
    data class Request(val method: String, val target: String, val body: ByteArray)

    fun read(socket: Socket, token: String, maxBytes: Int = 1024 * 1024): Request {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val input = BufferedInputStream(socket.getInputStream())
        fun checkDeadline() {
            val left = deadline - System.nanoTime()
            if (left <= 0) throw Rejected(408)
            socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(left).coerceIn(1, 10_000).toInt()
        }
        var headerBytes = 0
        fun line(): String {
            val text = StringBuilder()
            while (true) {
                checkDeadline()
                val ch = input.read()
                if (ch < 0) throw Rejected(400)
                if (++headerBytes > 16_384) throw Rejected(431)
                if (ch == 10) return text.toString().removeSuffix("\r")
                text.append(ch.toChar())
            }
        }
        val start = line().split(' ')
        if (start.size != 3 || start[2] != "HTTP/1.1") throw Rejected(400)
        val headers = HashMap<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0) throw Rejected(400)
            val name = header.substring(0, colon).lowercase(java.util.Locale.ROOT)
            val value = header.substring(colon + 1).trim()
            val old = headers[name]
            when {
                old == null -> headers[name] = value
                // 鉴权、长度、分块、主机这几个重复了才有歧义（请求走私），拒绝
                name in STRICT -> throw Rejected(400)
                // 其他头允许重复（Codex 的 HTTP 客户端会发两个 Accept），按 HTTP 规范合并
                else -> headers[name] = "$old, $value"
            }
        }
        if (headers["authorization"] != "Bearer $token") throw Rejected(401)
        if (headers.containsKey("transfer-encoding")) throw Rejected(400)
        val length = headers["content-length"]?.let { it.toLongOrNull() ?: throw Rejected(400) } ?: 0
        if (length !in 0..maxBytes.toLong()) throw Rejected(413)
        val body = ByteArray(length.toInt())
        var offset = 0
        while (offset < body.size) {
            checkDeadline()
            val n = input.read(body, offset, minOf(8192, body.size - offset))
            if (n < 0) throw Rejected(400)
            offset += n
        }
        return Request(start[0], start[1], body)
    }

    private val STRICT = setOf("authorization", "content-length", "transfer-encoding", "host")

    fun reject(socket: Socket, status: Int) {
        runCatching { socket.getOutputStream().write("HTTP/1.1 $status Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
    }
}

/** Bound waiting/running connections and release them all when the service stops. */
class BoundedSocketPool(name: String) : java.io.Closeable {
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val pool = ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8),
        { r -> Thread(r, name).apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val deadlines = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r -> Thread(r, "$name-deadlines").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }

    fun execute(socket: Socket, action: (Socket) -> Unit) {
        sockets.add(socket)
        try {
            val timeout = deadlines.schedule({ runCatching { socket.close() } }, 15, TimeUnit.MINUTES)
            try { pool.execute {
                try { action(socket) } finally { timeout.cancel(false); sockets.remove(socket); runCatching { socket.close() } }
            } } catch (e: java.util.concurrent.RejectedExecutionException) { timeout.cancel(false); throw e }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            sockets.remove(socket); runCatching { socket.close() }
        }
    }

    override fun close() {
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
        pool.shutdownNow(); deadlines.shutdownNow()
    }
}
