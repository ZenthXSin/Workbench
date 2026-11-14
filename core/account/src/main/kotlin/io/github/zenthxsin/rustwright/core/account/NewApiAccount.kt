package io.github.zenthxsin.rustwright.core.account

import io.github.zenthxsin.rustwright.core.runtime.net.RelayNodes

import io.github.zenthxsin.rustwright.core.runtime.Platform
import io.github.zenthxsin.rustwright.core.storage.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NewAPI 账号门面：OAuth（授权码 + PKCE，本机回环回调）登录、按授权创建专用 `sk-` 令牌、查询余额、登出。
 * 会话加密存于 [SecretStore]；浏览器经 [Platform.openUri] 打开。
 */
class NewApiAccount(
    private val secrets: SecretStore,
    private val platform: Platform,
    /** 固定配置（测试用）；为 null 时跟随 [RelayNodes] 选中的节点。 */
    private val fixed: NewApiOAuthConfig? = null,
) {
    private fun configFor(origin: String) = fixed ?: NewApiOAuthConfig(issuer = "$origin/oidc")
    private val config: NewApiOAuthConfig get() = configFor(RelayNodes.origin)
    private val repository = NewApiAccountRepository(object : AccountSessionStore {
        override fun load() = secrets.get(SESSION_KEY)
        override fun save(value: String?) = if (value == null) secrets.remove(SESSION_KEY) else secrets.put(SESSION_KEY, value)
    }, configFor(RelayNodes.origin))

    /** 每次调用前对齐当前节点；失败时按测速顺序换下一个节点再试。 */
    private suspend fun <T> onNode(block: suspend () -> T): T =
        if (fixed != null) block() else RelayNodes.withFallback { o -> repository.use(configFor(o)); block() }

    val loggedIn: Boolean get() = repository.hasSession
    /** 当前 OAuth 身份，用于使账号相关界面缓存失效；不是调用凭据。 */
    val subject: String? get() = repository.subject

    /** 已创建的中转站令牌；未登录或尚未创建时为 null。 */
    val relayKey: String? get() = repository.relayKey

    /** 打开系统浏览器授权，等待回调（最长 3 分钟），换取并保存会话。 */
    suspend fun login() = onNode { loginOn(repository.config) }

    private suspend fun loginOn(config: NewApiOAuthConfig) {
        val protocol = NewApiOAuthProtocol(config)
        val metadata = protocol.discover()
        val verifier = NewApiOAuthProtocol.random()
        val state = NewApiOAuthProtocol.random()
        val nonce = NewApiOAuthProtocol.random()
        val tokens = withContext(Dispatchers.IO) {
            LoopbackOAuthCallback(config.redirectUri, config.returnUri).use { callback ->
                platform.openUri(protocol.authorizationUrl(metadata, verifier, state, nonce))
                val code = callback.awaitCode(state, config.issuer)
                protocol.exchange(metadata, code, verifier, nonce)
            }
        }
        repository.accept(tokens)
    }

    /** 确保本次授权对应的专用令牌存在（每次授权只尝试创建一次），返回 `sk-` 令牌。 */
    suspend fun ensureRelayKey(): String = onNode { repository.ensureRelayKey() }

    suspend fun info(): NewApiAccountInfo = onNode { repository.account() }

    suspend fun benefits(): NewApiBenefits = onNode { repository.benefits() }

    /** Fixed, credential-free display messages; never expose raw response bodies. */
    fun benefitReadError(error: Throwable): String = when (error) {
        is NewApiException -> when (error.status) {
            404 -> "服务端尚未更新福利功能，请联系站点管理员"
            401 -> "登录已过期，请重新登录 NewAPI"
            403 -> "福利读取权限不足，请重新登录授权"
            429 -> "读取过于频繁，请稍后刷新"
            else -> "福利服务暂时不可用（HTTP ${error.status}），请稍后刷新"
        }
        else -> "暂时读不到福利状态，请检查网络后重试"
    }

    /** 固定福利节点，不使用可手填的模型线路，也不跨节点自动重放生成。 */
    suspend fun freeRelay(origin: String? = null): NewApiBenefitRelay {
        val selected = origin ?: config.origin
        check(selected == fixed?.origin || fixed == null && selected in RelayNodes.ORIGINS) { "福利节点不受信任" }
        return repository.freeRelay(selected)
    }

    /** Fixed-node operation: plugins supply their own schema and response validation. */
    suspend fun requestSelf(path: String, body: org.json.JSONObject, key: String, origin: String, subject: String): org.json.JSONObject {
        check(origin == fixed?.origin || fixed == null && origin in RelayNodes.ORIGINS) { "请求节点不受信任" }
        return repository.requestSelf(path, body, key, origin, subject)
    }

    val serviceOrigin: String get() = config.origin

    suspend fun logout() = onNode { repository.logout() }

    private companion object {
        const val SESSION_KEY = "newapi.session"
    }
}
