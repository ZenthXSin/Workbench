package io.github.zenthxsin.rustwright.core.ai.model

import io.github.zenthxsin.rustwright.core.ai.Relay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 拉取模型列表失败；[message] 已是给用户看的中文说明。 */
class ModelCatalogException(val httpStatus: Int?, message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** 一个节点的测速结果。 */
data class EndpointSelection(val baseUrl: String, val catalog: ModelCatalog, val elapsedMillis: Long)

/** `GET {baseUrl}/models`，并支持多节点并发测速选最快（RustPic FastestModelEndpointSelector）。 */
class ModelCatalogClient(private val client: OkHttpClient = defaultClient()) {

    suspend fun fetch(token: String, baseUrl: String = Relay.DEFAULT_BASE_URL): ModelCatalog {
        require(token.isNotBlank()) { "令牌不能为空" }
        val endpoint = baseUrl.trimEnd('/')
        require(endpoint.isNotBlank()) { "服务地址不能为空" }
        val request = Request.Builder().url("$endpoint/models").header("Authorization", "Bearer $token").get().build()
        val body = client.newCall(request).await { res ->
            if (!res.isSuccessful) throw ModelCatalogException(res.code, httpFailureMessage(res.code))
            res.body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= 4 * 1024 * 1024) { "模型列表过大" }
                    output.write(buffer, 0, n)
                }
                output.toString("UTF-8")
            }
        }
        val catalog = runCatching { ModelCatalog.parse(body) }
            .getOrElse { throw ModelCatalogException(null, "服务返回的模型列表格式有误，请联系服务商", it) }
        if (catalog.entries.isEmpty()) throw ModelCatalogException(null, "模型列表为空，请在服务商网站检查令牌的模型权限")
        return catalog
    }

    /** 并发请求所有节点，返回最快成功的一个；全部失败时抛出最有指导意义的错误（401 > 403 > 429 > 其他）。 */
    suspend fun selectFastest(token: String, endpoints: List<String>): EndpointSelection = coroutineScope {
        val addresses = endpoints.map { it.trimEnd('/') }.distinct().take(8)
        val results = Channel<Result<EndpointSelection>>(addresses.size.coerceAtLeast(1))
        val jobs = addresses.map { endpoint ->
            launch {
                val result =
                try {
                    val t0 = System.nanoTime()
                    val catalog = fetch(token, endpoint)
                    Result.success(EndpointSelection(endpoint, catalog, (System.nanoTime() - t0) / 1_000_000))
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    Result.failure(e)
                }
                results.send(result)
            }
        }
        val attempts = mutableListOf<Result<EndpointSelection>>()
        try {
            repeat(addresses.size) {
                val result = results.receive()
                result.getOrNull()?.let { return@coroutineScope it }
                attempts += result
            }
        } finally { jobs.forEach { it.cancel() }; results.close() }
        throw attempts.mapNotNull { it.exceptionOrNull() as? ModelCatalogException }
            .minByOrNull { when (it.httpStatus) { 401 -> 0; 403 -> 1; 429 -> 2; null -> 4; else -> 3 } }
            ?: ModelCatalogException(null, "无法连接模型服务，请检查网络和服务地址后重试")
    }

    companion object {
        fun httpFailureMessage(status: Int): String {
            val guidance = when (status) {
                401 -> "令牌无效或已过期，请检查后重新保存"
                402 -> "账户余额不足，请在服务商网站查看账户状态"
                403 -> "当前令牌没有访问模型的权限，请联系服务商"
                404 -> "未找到模型列表，请检查服务地址"
                408 -> "模型服务响应超时，请稍后重试"
                429 -> "请求过于频繁，请稍后重试"
                in 500..599 -> "模型服务暂时不可用，请稍后重试"
                else -> "无法获取模型列表，请稍后重试或联系服务商"
            }
            return "$guidance（HTTP $status）"
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }
}

/** OkHttp 调用转挂起；取消协程即取消请求。 */
internal suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isActive) return
            cont.resumeWithException(
                ModelCatalogException(null, if (e is InterruptedIOException) "连接模型服务超时，请检查网络后重试" else "无法连接模型服务，请检查网络后重试", e),
            )
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use(read) }
            if (cont.isActive) result.fold(cont::resume, cont::resumeWithException)
        }
    })
}
