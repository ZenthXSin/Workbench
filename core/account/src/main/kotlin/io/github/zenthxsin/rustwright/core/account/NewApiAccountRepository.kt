package io.github.zenthxsin.rustwright.core.account

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlinx.serialization.encodeToString

internal interface AccountSessionStore {
    fun load(): String?
    fun save(value: String?)
}

/** [role] 是 NewAPI 的用户角色：1 普通用户、10 管理员、100 超级管理员。 */
data class NewApiAccountInfo(val name: String, val balance: String, val rechargeAvailable: Boolean, val role: Int = 1) {
    val isAdmin: Boolean get() = role >= 10
}

/** 来自有效 NewAPI 登录的专用线路；token 只在内存或 SecretStore 中保存。 */
data class NewApiBenefitRelay(val baseUrl: String, val token: String, val subject: String, val imageModel: String, val textModels: List<String> = emptyList())

internal class AccountSession(val tokens: NewApiOAuthTokens, var tokenName: String = "Workbench-" + UUID.randomUUID().toString().replace("-", "").take(16),
    var attemptedCreation: Boolean = false, var relayId: Long? = null, var relayKey: String? = null, var relayGroup: String? = null) {
    fun encode(): String = JSONObject().put("access", tokens.accessToken).put("refresh", tokens.refreshToken)
        .put("expiry", tokens.expiresAt).put("subject", tokens.subject).put("name", tokenName)
        .put("attempted", attemptedCreation).put("relayId", relayId).put("relayKey", relayKey).put("relayGroup", relayGroup).toString()
    companion object {
        fun decode(raw: String): AccountSession {
            val o = JSONObject(raw)
            return AccountSession(NewApiOAuthTokens(o.getString("access"), o.getString("refresh"), null,
                o.getLong("expiry"), o.getString("subject")), o.getString("name"), o.getBoolean("attempted"),
                if (o.has("relayId")) o.getLong("relayId") else null, o.optString("relayKey").takeIf(String::isNotBlank),
                if (o.has("relayGroup")) o.getString("relayGroup") else if (o.getBoolean("attempted") || o.has("relayId")) NewApiAccountRepository.GROUP else null)
        }
    }
}

internal class NewApiAccountRepository(private val store: AccountSessionStore, config: NewApiOAuthConfig = NewApiOAuthConfig()) {
    private var protocol = NewApiOAuthProtocol(config)
    private val http get() = protocol.http
    var config: NewApiOAuthConfig = config
        private set

    /** 换节点（同一个后端的另一个入口）：会话不变，之后的请求发到新地址。 */
    suspend fun use(next: NewApiOAuthConfig) = lock.withLock {
        if (next == config) return@withLock
        config = next
        protocol = NewApiOAuthProtocol(next)
    }
    private val lock = Mutex()
    @Volatile private var session = store.load()?.let { runCatching { AccountSession.decode(it) }.getOrNull() }
    private var pendingRevocation: NewApiOAuthTokens? = null
    val hasSession: Boolean get() = session != null
    val subject: String? get() = session?.tokens?.subject
    val relayKey: String? get() = session?.relayKey

    suspend fun accept(tokens: NewApiOAuthTokens) = lock.withLock {
        session = AccountSession(tokens); save()
    }

    /** Exactly one create attempt per grant. An ambiguous response is reconciled by its unique name. */
    suspend fun ensureRelayKey(): String = lock.withLock {
        val current = authorized()
        ensureRelayKey(current)
    }

    private suspend fun ensureRelayKey(current: AccountSession): String {
        current.relayKey?.let { return it }
        val access = current.tokens.accessToken
        if (current.relayGroup == null) {
            val groups = data(http.json("/api/user/self/groups", access))
            // Empty group uses the authenticated account's normal default.
            // Welfare admission selects its hidden group on the server, so a
            // paid-group membership is never a prerequisite for free usage.
            current.relayGroup = if (groups.has(GROUP)) GROUP else ""
            save()
        }
        var id = current.relayId ?: findCreated(current)
        if (id == null && !current.attemptedCreation) {
            current.attemptedCreation = true; save()
            val result = http.json("/api/token/", access, JSONObject()
                .put("name", current.tokenName).put("group", current.relayGroup).put("expired_time", -1)
                .put("unlimited_quota", true).put("remain_quota", 0).put("model_limits_enabled", false)
                .put("model_limits", "").put("allow_ips", "").put("cross_group_retry", false))
            id = result.optJSONObject("data")?.optLong("id")?.takeIf { it > 0 } ?: findCreated(current)
        }
        check(id != null) { "令牌创建结果尚未确认，请刷新重试；不会重复提交创建请求" }
        current.relayId = id; save()
        val detail = data(http.json("/api/token/$id", access))
        check(detail.getString("name") == current.tokenName && detail.getString("group") == current.relayGroup &&
            detail.optInt("status", 1) == 1) { "令牌信息与本次授权不匹配" }
        // This endpoint only reveals keys derived from the current OAuth grant. Never use hosted reveal of an independent key.
        val revealed = http.json("/api/token/$id/key", access, JSONObject()).get("data")
        val raw = when (revealed) { is JSONObject -> revealed.getString("key"); is String -> revealed; else -> error("令牌响应格式错误") }
        check(raw.isNotBlank() && !raw.contains('*') && !raw.any(Char::isWhitespace)) { "服务端未返回可用令牌" }
        val key = if (raw.startsWith("sk-")) raw else "sk-$raw"
        current.relayKey = key; save()
        return key
    }

    private suspend fun findCreated(s: AccountSession): Long? {
        val result = http.json("/api/token/search?keyword=${URLEncoder.encode(s.tokenName, "UTF-8")}&p=1&page_size=50", s.tokens.accessToken)
        val items = result.optJSONArray("data") ?: result.optJSONObject("data")?.getJSONArray("items")
            ?: error("令牌列表格式错误")
        val matching = (0 until items.length()).map(items::getJSONObject).filter {
            it.optString("name") == s.tokenName && it.optString("group") == (s.relayGroup ?: GROUP) && it.optInt("status", 1) == 1 }
        check(matching.size <= 1) { "发现重复令牌，请在 NewAPI 中检查" }
        return matching.singleOrNull()?.getLong("id")
    }

    suspend fun account(): NewApiAccountInfo {
        val (tokens, transport) = lock.withLock { authorized().tokens to http }
        return kotlinx.coroutines.coroutineScope {
        val profileRequest = async { data(transport.json("/api/user/self", tokens.accessToken)) }
        val statusRequest = async { data(transport.json("/api/status")) }
        val walletRequest = async { data(transport.json("/api/user/topup/info", tokens.accessToken)) }
        val user = profileRequest.await()
        val status = statusRequest.await()
        val wallet = walletRequest.await()
        check(session?.tokens?.subject == tokens.subject) { "账号已切换" }
        NewApiAccountInfo(user.optString("display_name").ifBlank { user.optString("username") },
            formatBalance(user.get("quota").toString(), status),
            listOf("enable_redemption", "enable_online_topup", "enable_wechatpay_topup", "enable_stripe_topup",
                "enable_creem_topup", "enable_waffo_topup", "enable_waffo_pancake_topup").any { wallet.optBoolean(it) },
            user.optInt("role", 1))
        }
    }

    /** OAuth profile:read；只读状态不会创建或重置福利周期。 */
    suspend fun benefits(): NewApiBenefits {
        val (tokens, transport) = lock.withLock { authorized().tokens to http }
        val response = transport.json("/api/user/self/benefits", tokens.accessToken)
        check(response.get("success") == true) { "福利状态响应格式错误" }
        check(session?.tokens?.subject == tokens.subject) { "账号已切换" }
        return NewApiBenefits.parse(data(response))
    }

    /** No balance gate: a last successful image must remain recoverable at 0. */
    suspend fun freeRelay(origin: String): NewApiBenefitRelay = lock.withLock {
        val current = authorized()
        val direct = NewApiTransport(config.copy(issuer = "$origin/oidc"))
        val result = direct.json("/api/user/self/benefits", current.tokens.accessToken)
        check(result.get("success") == true) { "福利状态响应格式错误" }
        val available = NewApiBenefits.parse(data(result))
        check(available.enabled && (available.eligible || available.eligibilityReason == "review_required")) { "当前账号的免费福利暂不可用" }
        val imageModel = available.imageModel?.takeIf { it.startsWith(NewApiBenefits.MODEL_PREFIX) } ?: error("服务端尚未配置免费生图模型")
        NewApiBenefitRelay("$origin/v1", ensureRelayKey(current), current.tokens.subject, imageModel, available.textModels)
    }

    /** Authenticated idempotent self-service request pinned to the original node and account. */
    suspend fun requestSelf(path: String, body: JSONObject, key: String, origin: String, subject: String): JSONObject = lock.withLock {
        require(path.startsWith("/api/user/self/") && !path.contains("..") && '?' !in path && '#' !in path)
        require(key.matches(Regex("[A-Za-z0-9_-]{16,100}"))) { "请求编号格式错误" }
        val current = authorized()
        check(current.tokens.subject == subject) { "请登录发起请求的原 NewAPI 账号后重试" }
        val direct = NewApiTransport(config.copy(issuer = "$origin/oidc"))
        val result = direct.json(path, current.tokens.accessToken, body, key)
        check(result.get("success") == true) { "响应格式错误，请重试原请求" }
        data(result)
    }

    /**
     * 访问令牌快过期时用刷新令牌换新的。
     * 只有服务端明确拒绝刷新令牌（invalid_grant / 400 / 401）才算掉登录；网络断开、超时、换节点、服务端 5xx
     * 都保留原会话，下次再试。原来先删会话再请求，弱网或开 VPN 时一次失败就被登出（「频繁掉登录」）。
     * 最坏情况（服务端已轮换但响应丢了）下次重放旧刷新令牌会被拒，结果和原来一样是重新登录，不会更糟。
     */
    private suspend fun authorized(): AccountSession {
        val old = session ?: error("请先登录 NewAPI")
        if (old.tokens.expiresAt > System.currentTimeMillis() + 60_000) return old
        // Rotation and durable storage form one bounded transaction, independent of a screen's cancellation.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            val tokens = try {
                kotlinx.coroutines.withTimeout(60_000) { protocol.refresh(old.tokens) }
            } catch (e: NewApiException) {
                if (e.refreshResponse && e.code == "invalid_grant" && e.status in setOf(400, 401)) {
                    session = null; store.save(null)
                    throw NewApiException(401, e.code)
                }
                throw e
            }
            AccountSession(tokens, old.tokenName, old.attemptedCreation, old.relayId, old.relayKey, old.relayGroup)
                .also { session = it; save() }
        }
    }

    suspend fun logout() = lock.withLock {
        session?.let { pendingRevocation = it.tokens }
        session = null; store.save(null)
        pendingRevocation?.let { protocol.revoke(it); pendingRevocation = null }
        Unit
    }

    private fun save() = store.save(session?.encode())
    private fun data(value: JSONObject): JSONObject = value.getJSONObject("data")

    companion object {
        val GROUP: String get() = io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.relayGroup()
        fun formatBalance(quota: String, status: JSONObject): String {
            val value = BigDecimal(quota)
            val unit = status.opt("quota_per_unit")?.toString()?.toBigDecimalOrNull()
            if (unit == null || unit <= BigDecimal.ZERO || status.optString("quota_display_type") == "TOKENS")
                return "${value.stripTrailingZeros().toPlainString()} 配额"
            val usd = value.divide(unit, 10, RoundingMode.HALF_UP)
            val kind = status.optString("quota_display_type", "USD")
            val (symbol, rate) = when (kind) {
                "CNY" -> "¥" to status.opt("usd_exchange_rate")?.toString()?.toBigDecimalOrNull()
                "CUSTOM" -> status.optString("custom_currency_symbol", "¤") to status.opt("custom_currency_exchange_rate")?.toString()?.toBigDecimalOrNull()
                "USD" -> "$" to BigDecimal.ONE
                else -> return "${value.toPlainString()} 配额"
            }
            if (rate == null || rate <= BigDecimal.ZERO) return "${value.toPlainString()} 配额"
            return symbol + usd.multiply(rate).setScale(2, RoundingMode.HALF_UP).toPlainString()
        }
    }
}
