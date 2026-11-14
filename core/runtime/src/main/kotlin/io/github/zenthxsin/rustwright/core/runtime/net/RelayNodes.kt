package io.github.zenthxsin.rustwright.core.runtime.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import java.net.HttpURLConnection
import java.net.URI

/**
 * 中转站节点（同一个 New-API 后端的多个入口）。启动时并发测速，选最快能通的一个，
 * 对话 / 模型列表 / 账号登录都走它；选中的节点不通时按测速结果往后退。
 */
object RelayNodes {
    /** 配置的服务入口，见 [Endpoints]。 */
    val ORIGINS: List<String> get() = Endpoints.relayOrigins()

    /** 当前选中的节点（未测速前是主站）。 */
    @Volatile var origin: String = Endpoints.main()
        private set

    /** 按上次测速从快到慢排好的节点；通不了的排在最后。 */
    @Volatile var ranked: List<String> = ORIGINS
        private set

    fun isKnown(url: String): Boolean {
        val o = runCatching { URI(url.trim()).let { "${it.scheme}://${it.rawAuthority}" } }.getOrNull() ?: return false
        return o in ORIGINS || (devRelay && isLoopback(url))
    }

    /** 开发版（桌面跑脚本时 -Drw.devRelay=true）允许本机的假服务器；手机上永远是 false。 */
    private val devRelay: Boolean get() = System.getProperty("rw.devRelay") == "true"

    private fun isLoopback(url: String): Boolean =
        runCatching { URI(url.trim()).host }.getOrNull().let { it == "127.0.0.1" || it == "localhost" }

    /**
     * 服务地址白名单：带令牌的请求只能发往内置的中转站。地址被改过（配置文件被篡改、旧版本留下的自定义地址）
     * 就换回当前节点的 /v1，令牌不会被送到别的服务器。
     */
    fun trusted(url: String?): String {
        val u = url?.trim()?.trimEnd('/').orEmpty()
        return if (u.isNotEmpty() && isKnown(u) && (u.startsWith("https://") || devRelay)) u else "$origin/v1"
    }

    /** 并发请求每个节点的 /api/status（New-API 公开接口），按耗时排序，选最快的 2xx。 */
    suspend fun probe(timeoutMs: Int = 6000): String = coroutineScope {
        val results = Channel<Triple<String, Boolean, Long>>(ORIGINS.size)
        val probes = ORIGINS.map { o ->
            launch {
                val t0 = System.nanoTime()
                val ok = runCatching {
                    HttpFetch.get("$o/api/status", 0, timeoutMs, statusOnly = true)
                    true
                }.getOrDefault(false)
                results.send(Triple(o, ok, (System.nanoTime() - t0) / 1_000_000))
            }
        }
        try {
            repeat(ORIGINS.size) {
                val result = results.receive()
                if (result.second) {
                    origin = result.first
                    ranked = listOf(origin) + ORIGINS.filter { it != origin }
                    return@coroutineScope origin
                }
            }
            origin
        } finally { probes.forEach { it.cancel() }; results.close() }
    }

    /** 按测速顺序依次试 [block]，第一个成功的结果返回；全部失败抛最后一个错误。 */
    suspend fun <T> withFallback(block: suspend (origin: String) -> T): T {
        var last: Throwable? = null
        for (o in listOf(origin) + ranked.filter { it != origin }) {
            try {
                return block(o).also { if (o != origin) origin = o }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
            }
        }
        throw last ?: IllegalStateException("没有可用的节点")
    }

    /** 测速前同步可用的「当前 API 地址」（含 /v1）。 */
    fun apiBase(): String = "$origin/v1"
}
