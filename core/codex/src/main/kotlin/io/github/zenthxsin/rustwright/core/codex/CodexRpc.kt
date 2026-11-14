package io.github.zenthxsin.rustwright.core.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class CodexException(message: String, val code: String = "codex_error") : Exception(message)

/**
 * Codex app-server 的 JSON-RPC 连接：每行一条消息（stdio）。
 * 通知和服务端请求交给 [onMessage]（在读取线程上调用）；进程退出时 [onClosed]。
 */
class CodexRpc(
    private val input: java.io.InputStream,
    private val output: OutputStream,
    private val onMessage: (method: String, id: JsonElement?, params: JsonObject) -> Unit,
    private val onClosed: (CodexException) -> Unit,
) {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val nextId = AtomicLong(1)
    private val writer = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
        { task -> Thread(task, "codex-rpc-writer").apply { isDaemon = true } })
    @Volatile var closed = false
        private set

    private val reader = Thread({ readLoop() }, "codex-rpc").apply { isDaemon = true; start() }

    private fun readLoop() {
        val error = try {
            val lines = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            while (true) {
                val line = boundedLine(lines) ?: break
                if (line.isBlank()) continue
                val message = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                dispatch(message)
            }
            CodexException("Codex 进程已退出", "runtime_stopped")
        } catch (e: Exception) {
            CodexException("Codex 连接中断：${e.message}", "runtime_stopped")
        }
        fail(error)
    }

    private fun dispatch(message: JsonObject) {
        val method = (message["method"] as? JsonPrimitive)?.content
        val id = message["id"]
        if (method == null) {
            val key = (id as? JsonPrimitive)?.content?.toLongOrNull() ?: return
            val waiter = pending.remove(key) ?: return
            val err = message["error"] as? JsonObject
            if (err != null) waiter.completeExceptionally(CodexException((err["message"] as? JsonPrimitive)?.content ?: "Codex 请求失败", (err["code"] as? JsonPrimitive)?.content ?: "codex_error"))
            else waiter.complete(message["result"] ?: JsonNull)
            return
        }
        onMessage(method, id?.takeIf { it !is JsonNull }, message["params"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    private fun send(message: JsonObject): CompletableDeferred<Unit> {
        if (closed) throw CodexException("Codex 未连接", "runtime_stopped")
        val written = CompletableDeferred<Unit>()
        try { writer.execute {
            try {
                check(!closed)
                val text = json.encodeToString(JsonObject.serializer(), message)
                require(text.length <= 16 * 1024 * 1024) { "RPC message too large" }
                output.write((text + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
                written.complete(Unit)
            } catch (e: Exception) {
                val error = CodexException("Codex 写入失败：${e.message}", "runtime_stopped")
                written.completeExceptionally(error); fail(error)
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            val error = CodexException("Codex 写入队列已满或关闭", "runtime_stopped")
            fail(error); throw error
        }
        return written
    }

    suspend fun request(method: String, params: JsonObject, timeoutMillis: Long = 60_000): JsonElement {
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<JsonElement>()
        var written: CompletableDeferred<Unit>? = null
        pending[id] = waiter
        try {
            currentCoroutineContext().ensureActive()
            return withTimeout(timeoutMillis) {
                written = send(buildJsonObject { put("id", id); put("method", method); put("params", params) })
                waiter.await()
            }
        } catch (e: TimeoutCancellationException) {
            // 上层任务自身超时属于取消，不能伪装成普通 RPC 错误继续后续工作。
            if (written?.isCompleted != true) close()
            currentCoroutineContext().ensureActive()
            throw CodexException("Codex 响应超时：$method", "runtime_timeout")
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (written != null && written?.isCompleted != true) close()
            throw e
        } finally {
            pending.remove(id)
        }
    }

    fun notify(method: String, params: JsonObject = JsonObject(emptyMap())) =
        send(buildJsonObject { put("method", method); put("params", params) })

    fun respond(id: JsonElement, result: JsonElement) = send(buildJsonObject { put("id", id); put("result", result) })

    fun reject(id: JsonElement, message: String = "Rustwright 不支持这个请求") = send(buildJsonObject {
        put("id", id)
        put("error", buildJsonObject { put("code", -32601); put("message", message) })
    })

    suspend fun initialize(): JsonElement {
        val result = request("initialize", buildJsonObject {
            put("clientInfo", buildJsonObject { put("name", "rustwright"); put("title", "Rustwright"); put("version", "1.0.0") })
            put("capabilities", buildJsonObject { put("experimentalApi", true) })
        })
        notify("initialized")
        return result
    }

    @Synchronized
    private fun fail(error: CodexException) {
        if (closed) return
        closed = true
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        writer.shutdownNow()
        onClosed(error)
    }

    fun close() {
        fail(CodexException("Codex 已关闭", "runtime_stopped"))
        Thread({ runCatching { input.close() }; runCatching { output.close() } }, "codex-rpc-close").apply { isDaemon = true; start() }
    }

    companion object {
        private fun boundedLine(reader: BufferedReader): String? {
            val line = StringBuilder()
            while (true) {
                val next = reader.read()
                if (next < 0) return line.takeIf { it.isNotEmpty() }?.toString()
                if (next == 10) return line.toString()
                require(line.length < 16 * 1024 * 1024) { "RPC line too large" }
                line.append(next.toChar())
            }
        }
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonElement.objOrNull(): JsonObject? = this as? JsonObject
internal val JsonElement.text: String? get() = (this as? JsonPrimitive)?.content
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
