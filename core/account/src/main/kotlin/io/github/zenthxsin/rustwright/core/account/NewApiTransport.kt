package io.github.zenthxsin.rustwright.core.account

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class NewApiException(val status: Int, val code: String, val state: String? = null,
    val refreshResponse: Boolean = false) : IOException(
    when (status) {
        401 -> "登录已过期，请重新登录 NewAPI"
        403 -> "授权权限不足，请重新登录并同意令牌管理和账户读取权限"
        429 -> "请求过于频繁，请稍后再试"
        else -> "NewAPI 请求失败（HTTP $status），请稍后重试"
    },
)

internal class NewApiTransport(val config: NewApiOAuthConfig) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    fun trusted(url: String): String {
        val uri = java.net.URI(url)
        require(uri.userInfo == null && uri.fragment == null &&
            "${uri.scheme}://${uri.rawAuthority}" == config.origin) { "NewAPI 返回的地址不属于当前服务" }
        return url
    }

    suspend fun json(path: String, token: String? = null, body: JSONObject? = null, idempotencyKey: String? = null): JSONObject {
        val request = Request.Builder().url(trusted(config.origin + path))
        if (token != null) request.header("Authorization", "Bearer $token")
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey)
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        return execute(request.build())
    }

    suspend fun form(url: String, fields: Map<String, String>, allowEmpty: Boolean = false): JSONObject {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return execute(Request.Builder().url(trusted(url)).post(body).build(), allowEmpty)
    }

    private suspend fun execute(request: Request, allowEmpty: Boolean = false): JSONObject =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val result = runCatching { response.use {
                        val raw = response.body?.byteStream()?.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                check(out.size() + n <= 8 * 1024 * 1024) { "NewAPI 响应过大" }
                                out.write(buffer, 0, n)
                            }
                            out.toString("UTF-8")
                        }.orEmpty()
                        val data = runCatching { JSONObject(raw) }.getOrNull()
                        if (!response.isSuccessful || data?.optBoolean("success", true) == false) {
                            val error = data?.optJSONObject("error")
                            throw NewApiException(response.code, error?.optString("type") ?: data?.optString("error").orEmpty(),
                                error?.optString("state")?.takeIf { it.isNotBlank() })
                        }
                        data ?: if (allowEmpty && raw.isBlank()) JSONObject() else throw IOException("NewAPI 返回了无法识别的数据")
                    } }
                    continuation.resumeWith(result)
                }
            })
        }
}
