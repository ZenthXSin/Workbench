package io.github.zenthxsin.rustwright.core.ai.image

import io.github.zenthxsin.rustwright.core.runtime.RwLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

/** 一张生成结果：内联 base64，或需要再下载的 URL。 */
sealed interface ImageOutput {
    data class Inline(val base64: String) : ImageOutput
    data class Remote(val url: String) : ImageOutput
}

/** 生图请求参数。 */
data class ImageRequest(
    val prompt: String,
    val model: String,
    val size: String = "1024x1024",
    val count: Int = 1,
    val seed: Int? = null,
    val quality: String? = null,
    val outputFormat: String? = null,
)

/** 服务端返回非 2xx。 */
class ImageApiException(val statusCode: Int, message: String, val type: String? = null, val state: String? = null) : IllegalStateException(message)

/**
 * OpenAI 协议生图客户端：`/images/generations` 与 `/images/edits`（多参考图 + 蒙版）。
 * 移植自 RustPic OpenAIImageClient：HttpURLConnection（Android/JVM 通用）、429/5xx 指数退避重试、
 * 可轮询取消、响应体大小上限、日志脱敏。阻塞调用，请在 IO 线程执行。
 */
class ImageClient(
    private val token: String,
    baseUrl: String,
    private val isCancelled: () -> Boolean = { false },
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val timeouts: Timeouts = Timeouts(),
) {
    data class Timeouts(
        val connectMillis: Int = 50_000,
        val readMillis: Int = 600_000,
        val baseRetryDelayMillis: Long = 15_000,
        val maxRetryDelayMillis: Long = 60_000,
    )

    private val endpoint = baseUrl.trimEnd('/').also { require(it.isNotBlank()) { "图片服务地址不能为空" } }

    fun generate(request: ImageRequest, maxRetries: Int = DEFAULT_MAX_RETRIES, idempotencyKey: String? = null): List<ImageOutput> {
        require(request.count >= 1) { "图片数量必须至少为 1" }
        val body = buildJsonObject {
            put("model", request.model)
            put("prompt", request.prompt)
            put("n", request.count)
            put("size", request.size)
            put("response_format", "b64_json")
            request.seed?.let { put("seed", it) }
            request.quality?.takeIf(String::isNotBlank)?.let { put("quality", it) }
            request.outputFormat?.let { put("output_format", it) }
        }.toString()
        return execute("images/generations", body.toByteArray(), "application/json; charset=utf-8", maxRetries, idempotencyKey)
    }

    fun edit(request: ImageRequest, imagePngs: List<ByteArray>, maskPng: ByteArray? = null, maxRetries: Int = DEFAULT_MAX_RETRIES): List<ImageOutput> {
        require(imagePngs.isNotEmpty()) { "参考图不能为空" }
        require(request.count >= 1) { "图片数量必须至少为 1" }
        val boundary = "Workbench-${System.nanoTime()}"
        return execute("images/edits", multipart(boundary, request, imagePngs, maskPng), "multipart/form-data; boundary=$boundary", maxRetries)
    }

    private fun execute(path: String, body: ByteArray, contentType: String, maxRetries: Int, idempotencyKey: String? = null): List<ImageOutput> {
        require(maxRetries >= 1) { "重试次数必须至少为 1" }
        // A lost response may already have been billed. Only replay a server-deduplicated request.
        val maxRetries = if (idempotencyKey == null) 1 else maxRetries.coerceAtMost(3)
        require(idempotencyKey == null || idempotencyKey.matches(Regex("[A-Za-z0-9_-]{16,100}"))) { "图片请求编号格式错误" }
        var lastError: Throwable? = null
        for (attempt in 1..maxRetries) {
            checkNotCancelled()
            val started = System.nanoTime()
            val conn = (URL("$endpoint/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeouts.connectMillis
                readTimeout = timeouts.readMillis
                instanceFollowRedirects = false
                doOutput = true
                useCaches = false
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Length", body.size.toString())
                idempotencyKey?.let { setRequestProperty("Idempotency-Key", it) }
            }
            RwLog.d(TAG, "POST /$path attempt=$attempt/$maxRetries bytes=${body.size}")
            val cancelWatch = cancellationWatcher.scheduleAtFixedRate({
                if (isCancelled() || System.nanoTime() - started > java.util.concurrent.TimeUnit.MINUTES.toNanos(10))
                    runCatching { conn.disconnect() }
            }, 100, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
            try {
                conn.outputStream.use { it.write(body) }
                checkNotCancelled()
                val status = conn.responseCode
                val text = readResponse(conn, status)
                if (status !in 200..299) {
                    val info = runCatching { (json.parseToJsonElement(text) as? JsonObject)?.get("error") as? JsonObject }.getOrNull()
                    val type = (info?.get("type") as? JsonPrimitive)?.contentOrNull
                    val state = (info?.get("state") as? JsonPrimitive)?.contentOrNull
                    val error = ImageApiException(status, serverError(status, text, maxRetries, attempt < maxRetries), type, state)
                    if (status in RETRYABLE && attempt < maxRetries && type?.startsWith("rustwright_benefit_") != true) {
                        lastError = error
                        backoff(path, attempt, status)
                        continue
                    }
                    throw error
                }
                if (text.isBlank()) throw IllegalStateException(EMPTY_RESPONSE)
                val outputs = parseOutputs(text)
                if (outputs.isEmpty()) throw IllegalStateException(NO_OUTPUT)
                RwLog.i(TAG, "/$path done outputs=${outputs.size} ms=${(System.nanoTime() - started) / 1_000_000}")
                return outputs
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImageApiException) {
                throw e
            } catch (e: InterruptedIOException) {
                if (isCancelled()) throw CancellationException("图片生成已取消")
                if (attempt < maxRetries) { lastError = e; backoff(path, attempt, null); continue }
                throw IllegalStateException("图片生成超时，未收到 HTTP 响应（/$path，${timeouts.readMillis / 1000}s）", e)
            } catch (e: IOException) {
                if (isCancelled()) throw CancellationException("图片生成已取消")
                if (attempt < maxRetries) { lastError = e; backoff(path, attempt, null); continue }
                throw IllegalStateException("无法连接图片服务，重试 $maxRetries 次后仍失败：${safeDetail(e.message)}", e)
            } catch (e: IllegalStateException) {
                if (isCancelled()) throw CancellationException("图片生成已取消")
                if (attempt < maxRetries && e.message in RECEIVE_FAILURES) { lastError = e; backoff(path, attempt, null); continue }
                throw e
            } finally {
                cancelWatch.cancel(false)
                runCatching { conn.disconnect() }
            }
        }
        throw IllegalStateException("图片请求重试后仍然失败：${safeDetail(lastError?.message)}", lastError)
    }

    private fun readResponse(conn: HttpURLConnection, status: Int): String {
        val stream = (if (status in 200..299) conn.inputStream else conn.errorStream) ?: return ""
        val out = ByteArrayOutputStream()
        stream.use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                checkNotCancelled()
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_RESPONSE_BYTES) throw IllegalStateException("服务器响应过大")
                out.write(buf, 0, n)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun multipart(boundary: String, r: ImageRequest, images: List<ByteArray>, mask: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        fun line(s: String) = out.write(s.toByteArray())
        fun field(name: String, value: String) {
            line("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n"); out.write(value.toByteArray()); line("\r\n")
        }
        fun file(name: String, filename: String, data: ByteArray) {
            line("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\nContent-Type: image/png\r\n\r\n")
            out.write(data); line("\r\n")
        }
        images.forEachIndexed { i, img -> file("image", "reference-${i + 1}.png", img) }
        mask?.let { file("mask", "mask.png", it) }
        field("prompt", r.prompt)
        field("model", r.model)
        field("n", r.count.toString())
        field("size", r.size)
        field("response_format", "b64_json")
        r.seed?.let { field("seed", it.toString()) }
        r.quality?.takeIf(String::isNotBlank)?.let { field("quality", it) }
        line("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun backoff(path: String, attempt: Int, status: Int?) {
        var remaining = minOf(timeouts.baseRetryDelayMillis shl (attempt - 1).coerceIn(0, 2), timeouts.maxRetryDelayMillis)
        RwLog.w(TAG, "/$path retry after ${remaining}ms (status=${status ?: "transport"})")
        while (remaining > 0) {
            checkNotCancelled()
            val step = minOf(remaining, 100L)
            sleeper(step)
            remaining -= step
        }
    }

    private fun checkNotCancelled() {
        if (isCancelled()) throw CancellationException("图片生成已取消")
    }

    private fun serverError(code: Int, body: String, maxRetries: Int, willRetry: Boolean): String {
        val detail = errorDetail(body)
        if (code == 429 && !willRetry) return "图片服务上游请求过于频繁，重试 $maxRetries 次后仍被限流，请稍后再试" + (detail?.let { "，服务端详情：$it" } ?: "")
        return if (detail == null) "请求失败：HTTP $code" else "请求失败：HTTP $code，$detail"
    }

    private fun errorDetail(body: String): String? {
        val trimmed = body.trim().ifEmpty { return null }
        val msg = runCatching {
            val o = json.parseToJsonElement(trimmed) as JsonObject
            ((o["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: (o["message"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        }.getOrNull()
        return safeDetail(msg ?: trimmed).takeIf { it != "网络请求失败" }
    }

    private fun safeDetail(message: String?): String {
        val d = message?.trim().orEmpty().ifEmpty { return "网络请求失败" }
        return RwLog.redact(d.replace(token, "***")).take(500)
    }

    companion object {
        private val cancellationWatcher = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "image-cancellation").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private const val TAG = "image"
        private const val DEFAULT_MAX_RETRIES = 3
        private const val MAX_RESPONSE_BYTES = 32L * 1024 * 1024
        private const val MAX_INLINE_BASE64_CHARS = 24 * 1024 * 1024
        private const val EMPTY_RESPONSE = "服务器返回空响应"
        private const val NO_OUTPUT = "图片响应缺少有效的 b64_json 或 url"
        private const val BAD_JSON = "服务器返回了无法解析的 JSON 响应"
        private val RECEIVE_FAILURES = setOf(EMPTY_RESPONSE, NO_OUTPUT, BAD_JSON)
        private val RETRYABLE = (500..599).toSet() + 429
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 解析 data / images 数组；兼容 b64_json / base64 / image_base64 / url 以及纯字符串元素。 */
        fun parseOutputs(body: String): List<ImageOutput> {
            val root: JsonElement = runCatching { json.parseToJsonElement(body) }.getOrElse { throw IllegalStateException(BAD_JSON, it) }
            val items = when (root) {
                is JsonObject -> (root["data"] ?: root["images"]) as? JsonArray
                is JsonArray -> root
                else -> null
            } ?: throw IllegalStateException("图片响应缺少 data")
            return items.mapNotNull { item ->
                when (item) {
                    is JsonObject -> {
                        val inline = listOf("b64_json", "base64", "image_base64")
                            .mapNotNull { (item[it] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                            .firstOrNull { it.length <= MAX_INLINE_BASE64_CHARS }
                        inline?.let(ImageOutput::Inline)
                            ?: (item["url"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.let(ImageOutput::Remote)
                    }
                    is JsonPrimitive -> item.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.let {
                        if (it.startsWith("http://") || it.startsWith("https://")) ImageOutput.Remote(it) else ImageOutput.Inline(it)
                    }
                    else -> null
                }
            }
        }
    }
}
