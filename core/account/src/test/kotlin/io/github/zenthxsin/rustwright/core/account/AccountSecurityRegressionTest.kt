package io.github.zenthxsin.rustwright.core.account

import io.github.zenthxsin.rustwright.core.runtime.net.RelayNodes
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AccountSecurityRegressionTest {
    private class Store : AccountSessionStore {
        var raw: String? = null
        override fun load() = raw
        override fun save(value: String?) { raw = value }
    }
    private fun metadata(origin: String) = JSONObject().put("issuer", "$origin/oidc")
        .put("authorization_endpoint", "$origin/oidc/authorize").put("token_endpoint", "$origin/oidc/token")
        .put("jwks_uri", "$origin/oidc/jwks").put("revocation_endpoint", "$origin/oidc/revoke").toString()

    @Test fun cancelledRefreshPersistsRotatedToken() = runBlocking {
        MockWebServer().use { server ->
            val origin = "http://127.0.0.1:${server.port}"
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val issued = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/oidc/.well-known/openid-configuration" -> MockResponse().setBody(metadata(origin))
                    "/oidc/token" -> {
                        issued.incrementAndGet()
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        MockResponse().setBody("""{"token_type":"Bearer","access_token":"new-access-fixture","refresh_token":"new-refresh-fixture","expires_in":3600}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val store = Store()
            val repo = NewApiAccountRepository(store, NewApiOAuthConfig(issuer = "$origin/oidc"))
            repo.accept(NewApiOAuthTokens("old-access-fixture", "old-refresh-fixture", null, 0, "user-fixture"))
            val job = async(Dispatchers.Default) { repo.benefits() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                job.cancel()
                release.countDown()
                job.join()
                assertEquals(1, issued.get())
                val saved = AccountSession.decode(store.raw!!)
                assertEquals("new-refresh-fixture", saved.tokens.refreshToken)
            } finally { release.countDown(); job.cancelAndJoin() }
        }
    }

    @Test fun discoveryBadRequestPreservesSession() = runBlocking {
        for (code in listOf("invalid_request", "invalid_grant")) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"$code"}"""))
            val store = Store()
            val repo = NewApiAccountRepository(store, NewApiOAuthConfig(issuer = "http://127.0.0.1:${server.port}/oidc"))
            repo.accept(NewApiOAuthTokens("access-fixture", "refresh-fixture", null, 0, "user-fixture"))
            assertFailsWith<NewApiException> { repo.benefits() }
            assertTrue(repo.hasSession)
            assertNotNull(store.raw)
            assertEquals("/oidc/.well-known/openid-configuration", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        }
        }
    }

    @Test fun logoutRetriesRevocationAndReportsFailure() = runBlocking {
        MockWebServer().use { server ->
            val origin = "http://127.0.0.1:${server.port}"
            val revocations = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/oidc/.well-known/openid-configuration" -> MockResponse().setBody(metadata(origin))
                    "/oidc/revoke" -> { revocations.incrementAndGet(); MockResponse().setResponseCode(503) }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val store = Store()
            val repo = NewApiAccountRepository(store, NewApiOAuthConfig(issuer = "$origin/oidc"))
            repo.accept(NewApiOAuthTokens("access-fixture", "refresh-fixture", null, Long.MAX_VALUE, "user-fixture"))
            var attempts = 0
            // Same composition as NewApiAccount.onNode/logout; all HTTP stays on this local server.
            assertFailsWith<NewApiException> { RelayNodes.withFallback { attempts++; repo.logout() } }
            val nodeCount = (listOf(RelayNodes.origin) + RelayNodes.ranked.filter { it != RelayNodes.origin }).size
            assertEquals(nodeCount, attempts)
            assertEquals(nodeCount, revocations.get())
            assertFailsWith<NewApiException> { repo.logout() }
            assertEquals(nodeCount + 1, revocations.get())
            assertNull(store.raw)
        }
    }
}
