package io.github.zenthxsin.rustwright.core.account

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 刷新访问令牌时，只有服务端明确拒绝才登出；网络问题、5xx 都保留会话。 */
class RefreshKeepsSessionTest {
    private class Store : AccountSessionStore {
        var value: String? = null
        override fun load() = value
        override fun save(value: String?) { this.value = value }
    }

    private fun run(token: (RecordedRequest) -> MockResponse, discovery: Boolean = true, check: suspend (NewApiAccountRepository, Store, MockWebServer) -> Unit) = runTest {
        MockWebServer().use { server ->
            val origin = "http://127.0.0.1:${server.port}"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/oidc/.well-known/openid-configuration" -> if (!discovery) MockResponse().setResponseCode(503) else MockResponse().setBody(JSONObject()
                        .put("issuer", "$origin/oidc").put("authorization_endpoint", "$origin/oidc/authorize").put("token_endpoint", "$origin/oidc/token")
                        .put("jwks_uri", "$origin/oidc/jwks").put("revocation_endpoint", "$origin/oidc/revoke").toString())
                    "/oidc/token" -> token(request)
                    "/api/user/self/benefits" -> MockResponse().setBody(JSONObject().put("success", true).put("data", JSONObject().put("enabled", false)).toString())
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val store = Store()
            val repo = NewApiAccountRepository(store, NewApiOAuthConfig(issuer = "$origin/oidc"))
            // 已过期的访问令牌，下一次请求必须先刷新
            repo.accept(NewApiOAuthTokens("old-access", "old-refresh", null, System.currentTimeMillis() - 1000, "user-1"))
            check(repo, store, server)
        }
    }

    @Test fun discoveryOutageKeepsSession() = run({ MockResponse().setResponseCode(500) }, discovery = false) { repo, store, _ ->
        assertFails { repo.benefits() }
        assertTrue(repo.hasSession)
        assertTrue(store.value!!.contains("old-refresh"))
    }

    @Test fun droppedConnectionOrServerErrorKeepsSession() {
        run({ MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }) { repo, store, _ ->
            assertFails { repo.benefits() }
            assertTrue(repo.hasSession)
            assertTrue(store.value!!.contains("old-refresh"))
        }
        run({ MockResponse().setResponseCode(502) }) { repo, _, _ ->
            assertFails { repo.benefits() }
            assertTrue(repo.hasSession)
        }
    }

    @Test fun rejectedRefreshTokenLogsOut() = run({ MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""") }) { repo, store, _ ->
        assertFails { repo.benefits() }
        assertFalse(repo.hasSession)
        assertEquals(null, store.value)
    }

    @Test fun successfulRefreshRotatesAndContinues() = run({ req ->
        assertTrue(req.body.readUtf8().contains("refresh_token=old-refresh"))
        MockResponse().setBody(JSONObject().put("token_type", "Bearer").put("access_token", "new-access")
            .put("refresh_token", "new-refresh").put("expires_in", 3600).toString())
    }) { repo, store, server ->
        repo.benefits()
        assertTrue(store.value!!.contains("new-refresh"))
        val last = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }.last()
        assertEquals("Bearer new-access", last.getHeader("Authorization"))
    }
}
