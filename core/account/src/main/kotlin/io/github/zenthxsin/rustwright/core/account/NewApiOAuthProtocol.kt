package io.github.zenthxsin.rustwright.core.account

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import org.json.JSONObject

// Credentials intentionally have no generated toString().
class NewApiOAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresAt: Long,
    val subject: String,
)

internal class NewApiOAuthProtocol(val config: NewApiOAuthConfig) {
    val http = NewApiTransport(config)
    suspend fun discover(): JSONObject {
        val data = http.json(java.net.URI(config.issuer).path + "/.well-known/openid-configuration")
        check(data.getString("issuer") == config.issuer) { "OAuth 发行者校验失败" }
        listOf("authorization_endpoint", "token_endpoint", "jwks_uri", "revocation_endpoint").forEach {
            http.trusted(data.getString(it))
        }
        return data
    }

    fun authorizationUrl(metadata: JSONObject, verifier: String, state: String, nonce: String): String {
        val fields = linkedMapOf(
            "response_type" to "code", "client_id" to config.clientId, "redirect_uri" to config.redirectUri,
            "scope" to config.scopes.joinToString(" "), "state" to state, "nonce" to nonce,
            "code_challenge" to b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))),
            "code_challenge_method" to "S256",
        )
        return http.trusted(metadata.getString("authorization_endpoint")) + "?" + fields.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
    }

    suspend fun exchange(metadata: JSONObject, code: String, verifier: String, nonce: String): NewApiOAuthTokens {
        val result = http.form(metadata.getString("token_endpoint"), mapOf(
            "grant_type" to "authorization_code", "code" to code, "code_verifier" to verifier,
            "redirect_uri" to config.redirectUri, "client_id" to config.clientId,
        ))
        return tokens(result, metadata, nonce = nonce)
    }

    suspend fun refresh(old: NewApiOAuthTokens): NewApiOAuthTokens {
        val metadata = discover()
        val result = try { http.form(metadata.getString("token_endpoint"), mapOf(
            "grant_type" to "refresh_token", "refresh_token" to requireNotNull(old.refreshToken), "client_id" to config.clientId,
        )) } catch (e: NewApiException) { throw NewApiException(e.status, e.code, e.state, refreshResponse = true) }
        return tokens(result, metadata, subject = old.subject, refreshing = true)
    }

    suspend fun revoke(tokens: NewApiOAuthTokens) {
        val metadata = discover()
        http.form(metadata.getString("revocation_endpoint"), mapOf("client_id" to config.clientId,
            "token" to (tokens.refreshToken ?: tokens.accessToken)), allowEmpty = true)
    }

    private suspend fun tokens(data: JSONObject, metadata: JSONObject, nonce: String? = null, subject: String? = null, refreshing: Boolean = false): NewApiOAuthTokens {
        check(data.getString("token_type").equals("Bearer", true)) { "不支持的 OAuth 令牌类型" }
        val access = data.getString("access_token").also { check(it.isNotBlank()) }
        val refresh = data.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: error("服务端没有授予离线登录权限，请重新授权")
        val id = data.optString("id_token").takeIf { it.isNotBlank() }
        // 刷新时不再联网拉 JWKS 验签：新令牌已经到手，这一步断网会把刚轮换出来的刷新令牌丢掉。
        // 令牌来自受信任的 token 端点（TLS），只核对 sub 没换人。
        val sub = when {
            id != null && refreshing -> unverifiedSubject(id) ?: subject ?: error("缺少登录身份凭据")
            id != null -> verifyId(id, access, metadata, nonce)
            else -> subject ?: error("缺少登录身份凭据")
        }
        check(subject == null || subject == sub) { "OAuth 刷新返回了不同账号" }
        val seconds = data.getLong("expires_in").also { check(it in 1..86400) }
        return NewApiOAuthTokens(access, refresh, id, System.currentTimeMillis() + seconds * 1000, sub)
    }

    private fun unverifiedSubject(id: String): String? = runCatching {
        val parts = id.split('.')
        JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)).optString("sub").takeIf { it.isNotBlank() }
    }.getOrNull()

    private suspend fun verifyId(id: String, access: String, metadata: JSONObject, nonce: String?): String {
        val parts = id.split('.'); check(parts.size == 3) { "登录身份凭据格式错误" }
        val decoder = Base64.getUrlDecoder()
        val header = JSONObject(String(decoder.decode(parts[0]), Charsets.UTF_8))
        val claims = JSONObject(String(decoder.decode(parts[1]), Charsets.UTF_8))
        check(header.optString("alg") == "RS256" && !header.has("crit")) { "不支持的身份签名" }
        val jwksUrl = java.net.URI(metadata.getString("jwks_uri"))
        val keys = http.json(jwksUrl.rawPath + (jwksUrl.rawQuery?.let { "?$it" } ?: "")).getJSONArray("keys")
        val key = (0 until keys.length()).map { keys.getJSONObject(it) }.singleOrNull {
            it.optString("kid") == header.getString("kid") && it.optString("kty") == "RSA" &&
                it.optString("use", "sig") == "sig" && it.optString("alg", "RS256") == "RS256"
        } ?: error("无法验证登录签名")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decoder.decode(key.getString("n"))), BigInteger(1, decoder.decode(key.getString("e")))))
        check(Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)); verify(decoder.decode(parts[2]))
        }) { "登录身份签名无效" }
        val now = System.currentTimeMillis() / 1000
        val audience = claims.optJSONArray("aud")?.let { a -> (0 until a.length()).map(a::getString) }
            ?: listOf(claims.getString("aud"))
        check(claims.getString("iss") == config.issuer && config.clientId in audience &&
            claims.getLong("exp") > now - 30 && claims.getLong("iat") <= now + 30 &&
            (!claims.has("nbf") || claims.getLong("nbf") <= now + 30) &&
            (audience.size <= 1 || claims.optString("azp") == config.clientId) &&
            (!claims.has("azp") || claims.getString("azp") == config.clientId) &&
            (nonce == null || claims.optString("nonce") == nonce)) { "登录身份校验失败" }
        if (claims.has("at_hash")) {
            check(claims.getString("at_hash") == b64(MessageDigest.getInstance("SHA-256")
                .digest(access.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 16))) { "登录令牌校验失败" }
        }
        return claims.getString("sub").also { check(it.isNotBlank()) }
    }

    companion object {
        fun random(): String = b64(ByteArray(32).also(SecureRandom()::nextBytes))
        fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
