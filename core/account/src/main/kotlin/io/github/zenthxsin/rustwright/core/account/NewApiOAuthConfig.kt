package io.github.zenthxsin.rustwright.core.account

/** Public-client settings for the NewAPI OAuth authorization-code + PKCE flow. */
data class NewApiOAuthConfig(
    val issuer: String = DEFAULT_ISSUER,
    val clientId: String = DEFAULT_CLIENT_ID,
    val redirectUri: String = DEFAULT_REDIRECT_URI,
    val scopes: Set<String> = DEFAULT_SCOPES,
    /** 浏览器收到授权结果后跳回软件的地址（Android 上由主界面的 intent-filter 接住）；null 时只显示文字。 */
    val returnUri: String? = DEFAULT_RETURN_URI,
) {
    val origin: String get() = java.net.URI(issuer).let { "${it.scheme}://${it.rawAuthority}" }

    init {
        val source = java.net.URI(issuer)
        require(source.scheme == "https" || (source.scheme == "http" && source.host == "127.0.0.1"))
        require(source.host != null && source.userInfo == null && source.fragment == null && source.query == null)
        require(!issuer.endsWith('/'))
        require(clientId.isNotBlank())
        val callback = java.net.URI(redirectUri)
        require(callback.scheme == "http" && callback.host == "127.0.0.1" && callback.port in 1..65535 &&
            callback.userInfo == null && callback.fragment == null && callback.query == null && callback.path == "/oauth/callback") {
            "OAuth 回调必须是本机 /oauth/callback 地址"
        }
        require(scopes.containsAll(REQUIRED_SCOPES))
    }

    companion object {
        val DEFAULT_ISSUER: String get() = io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.main() + "/oidc"
        val DEFAULT_CLIENT_ID: String get() = io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.configured("oauth.client_id", "workbench")
        const val DEFAULT_REDIRECT_URI = "http://127.0.0.1:8787/oauth/callback"
        val DEFAULT_RETURN_URI: String get() = io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.configured("oauth.return_uri", "workbench://login")
        val REQUIRED_SCOPES = setOf("openid", "profile", "offline_access", "profile:read", "relay:invoke")
        val DEFAULT_SCOPES = REQUIRED_SCOPES + setOf("email", "relay:read", "relay:tasks", "wallet:read", "api_key:read", "api_key:write", "api_key:reveal")
    }
}
