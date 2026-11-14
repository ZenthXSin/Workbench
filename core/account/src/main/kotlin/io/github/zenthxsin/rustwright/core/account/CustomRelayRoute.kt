package io.github.zenthxsin.rustwright.core.account

import io.github.zenthxsin.rustwright.core.storage.SecretStore
import java.net.URI

/**
 * 管理员的自定义线路：付费模型改走自己填的 OpenAI 兼容地址和密钥（开发者工具里设置）。
 *
 * - 地址和密钥加密存在 [SecretStore]，不进日志。
 * - 只对**开启它的那个管理员账号**生效：存下开启时的 OAuth 身份，换号、退出登录就自动失效；
 *   发现当前账号不是管理员时调用 [revoke] 关掉。
 * - 自定义地址只会收到这里填的密钥；NewAPI 登录得到的令牌仍只发给内置中转站。
 */
class CustomRelayRoute(private val secrets: SecretStore, private val subject: () -> String?) {
    data class Route(val baseUrl: String, val apiKey: String) {
        override fun toString() = "Route($baseUrl, ${mask(apiKey)})"
    }

    val baseUrl: String? get() = secrets.get(BASE)
    val apiKey: String? get() = secrets.get(KEY)
    val enabled: Boolean get() = secrets.get(OWNER) != null

    /** 当前生效的线路；没开、没填全、或登录的不是开启它的账号时为 null。 */
    val active: Route?
        get() {
            val owner = secrets.get(OWNER) ?: return null
            if (owner != subject()) return null
            val base = baseUrl ?: return null
            val key = apiKey?.takeIf { it.isNotBlank() } ?: return null
            return Route(base, key)
        }

    /** 保存地址（规范化后返回）；地址不合法抛 [IllegalArgumentException]。 */
    fun setBaseUrl(raw: String): String = normalize(raw).also { secrets.put(BASE, it) }

    fun setApiKey(raw: String) {
        val key = raw.trim()
        require(key.isNotEmpty()) { "密钥不能为空" }
        require(key.none { it.isWhitespace() || it == '"' || it == '\'' || it.code < 0x20 }) { "密钥里有空格或引号" }
        secrets.put(KEY, key)
    }

    /** 开启（绑定到当前登录账号）或关闭。开启前地址和密钥都要填好。 */
    fun setEnabled(on: Boolean) {
        if (!on) { secrets.remove(OWNER); return }
        val owner = subject() ?: error("请先登录管理员账号")
        check(baseUrl != null && !apiKey.isNullOrBlank()) { "先填好地址和密钥" }
        secrets.put(OWNER, owner)
    }

    /** 当前账号不是管理员：关掉（地址和密钥留着，管理员再登录可以直接打开）。 */
    fun revoke() = secrets.remove(OWNER)

    /** 清空地址、密钥并关闭。 */
    fun clear() { secrets.remove(OWNER); secrets.remove(BASE); secrets.remove(KEY) }

    companion object {
        private const val BASE = "dev.customRoute.base"
        private const val KEY = "dev.customRoute.key"
        private const val OWNER = "dev.customRoute.owner"

        /**
         * 规范化成 Codex 的 `base_url`：去掉首尾空白和末尾斜杠，只有域名时补 `/v1`。
         * 只接受 http(s)、不带账号密码 / 查询串 / 片段，也不允许引号（会拼进 Codex 启动参数）。
         */
        fun normalize(raw: String): String {
            val s = raw.trim().trimEnd('/')
            require(s.isNotEmpty()) { "地址不能为空" }
            require(s.none { it.isWhitespace() || it == '"' || it == '\'' || it == '\\' || it.code < 0x20 }) { "地址里有空格或引号" }
            val u = runCatching { URI(s) }.getOrElse { throw IllegalArgumentException("地址格式不对") }
            require(u.scheme == "https" || u.scheme == "http") { "地址要以 https:// 或 http:// 开头" }
            require(!u.host.isNullOrBlank()) { "地址缺少域名" }
            require(u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null) { "地址不能带账号、? 参数或 #" }
            return if (u.rawPath.isNullOrEmpty()) "$s/v1" else s
        }

        fun isPlainHttp(url: String) = url.startsWith("http://")

        fun mask(key: String): String = if (key.length <= 8) "••••" else key.take(3) + "…" + key.takeLast(4)
    }
}
