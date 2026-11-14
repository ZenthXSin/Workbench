package io.github.zenthxsin.rustwright.core.account

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NewApiBenefitsTest {
    private class Store : AccountSessionStore {
        var value: String? = null
        override fun load() = value
        override fun save(value: String?) { this.value = value }
    }

    @Test fun readsAuthenticatedSnapshotWithoutStartingCyclesOrCreatingKeys() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(JSONObject().put("success", true).put("data", snapshot()).toString()))
            val store = Store()
            val repo = NewApiAccountRepository(store, NewApiOAuthConfig(issuer = "http://127.0.0.1:${server.port}/oidc"))
            repo.accept(NewApiOAuthTokens("test-oauth-access", "refresh", null, System.currentTimeMillis() + 3_600_000, "test-user"))
            val before = store.value
            val result = repo.benefits()
            assertTrue(result.textAvailable)
            assertTrue(result.generationAvailable)
            assertEquals(1_000_000L, result.balance!!.fiveHours.limit)
            assertEquals(15_000_000L, result.balance!!.sevenDays.remaining)
            assertEquals(0L, result.balance!!.fiveHours.startsAt)
            assertEquals(10L, result.balance!!.generation.limit)
            assertEquals("Asia/Shanghai", result.balance!!.dayTimezone)
            assertEquals(before, store.value)
            assertEquals(null, repo.relayKey)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/api/user/self/benefits", request.path)
            assertEquals("Bearer test-oauth-access", request.getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun disabledAndIneligibleHaveNoInventedBalance() {
        val disabled = NewApiBenefits.parse(JSONObject().put("enabled", false))
        assertFalse(disabled.available)
        assertEquals(null, disabled.balance)
        val ineligible = NewApiBenefits.parse(JSONObject().put("enabled", true).put("eligible", false).put("eligibility_reason", "account_disabled"))
        assertFalse(ineligible.textAvailable)
        assertEquals(null, ineligible.balance)
    }

    @Test fun reviewAndExhaustionNeverGrantAFreeCall() {
        val review = snapshot().apply { getJSONObject("balance").put("needs_review", true) }
        assertFalse(NewApiBenefits.parse(review).available)
        val exhausted = snapshot().apply { getJSONObject("balance").getJSONObject("five_hours").put("remaining", 0) }
        val result = NewApiBenefits.parse(exhausted)
        assertFalse(result.textAvailable)
        assertTrue(result.generationAvailable)
        assertEquals(listOf("workbench-free/deepseek-test"), result.textModels)
    }

    @Test fun malformedBalancesAndNonFreeAliasesFailClosed() {
        val invalid = listOf(
            snapshot().apply { remove("balance") },
            snapshot().apply { getJSONObject("balance").put("needs_review", 0) },
            snapshot().apply { getJSONObject("balance").getJSONObject("five_hours").put("remaining", -1) },
            snapshot().apply { getJSONObject("balance").getJSONObject("generation").put("limit", 10.5) },
            snapshot().apply { getJSONObject("models").put("image", "gpt-image-2") },
        )
        invalid.forEach { assertFails { NewApiBenefits.parse(it) } }
    }

    @Test fun missingLoginAndRejectedScopeDoNotReturnQuota() = runTest {
        MockWebServer().use { server ->
            server.start()
            val repo = NewApiAccountRepository(Store(), NewApiOAuthConfig(issuer = "http://127.0.0.1:${server.port}/oidc"))
            assertFailsWith<IllegalStateException> { repo.benefits() }
            assertEquals(0, server.requestCount)
            repo.accept(NewApiOAuthTokens("test-access", "refresh", null, System.currentTimeMillis() + 3_600_000, "user"))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"error":"insufficient_scope"}"""))
            assertFailsWith<NewApiException> { repo.benefits() }
            assertTrue(NewApiOAuthConfig.REQUIRED_SCOPES.contains("profile:read"))
        }
    }

    @Test fun freeLoginWithoutPaidGroupCreatesOneGrantAndCanRecoverAtZero() = runTest {
        MockWebServer().use { server ->
            server.start()
            val origin = "http://127.0.0.1:${server.port}"
            val store = Store()
            val config = NewApiOAuthConfig(issuer = "$origin/oidc")
            val repo = NewApiAccountRepository(store, config)
            assertFailsWith<IllegalStateException> { repo.freeRelay(origin) }
            assertEquals(0, server.requestCount)
            repo.accept(NewApiOAuthTokens("oauth-login", "refresh", null, System.currentTimeMillis() + 3_600_000, "7"))
            val session = AccountSession.decode(store.value!!)
            val exhausted = snapshot().apply { getJSONObject("balance").getJSONObject("generation").put("remaining", 0) }
            fun response(data: Any) = MockResponse().setBody(JSONObject().put("success", true).put("data", data).toString())
            server.enqueue(response(exhausted))
            server.enqueue(response(JSONObject().put("default", 1)))
            server.enqueue(response(org.json.JSONArray()))
            server.enqueue(response(JSONObject().put("id", 42)))
            server.enqueue(response(JSONObject().put("name", session.tokenName).put("group", "").put("status", 1)))
            server.enqueue(response(JSONObject().put("key", "test-dedicated-key")))
            val relay = repo.freeRelay(origin)
            assertEquals("$origin/v1", relay.baseUrl)
            assertEquals("sk-test-dedicated-key", relay.token)
            assertEquals("7", relay.subject)
            val requests = (1..6).map { server.takeRequest(1, TimeUnit.SECONDS)!! }
            assertTrue(requests.all { it.getHeader("Authorization") == "Bearer oauth-login" })
            val creation = requests.single { it.path == "/api/token/" }
            assertEquals("", JSONObject(creation.body.readUtf8()).getString("group"))
            server.enqueue(response(exhausted))
            val reloaded = NewApiAccountRepository(store, config)
            assertEquals(relay, reloaded.freeRelay(origin))
            assertEquals(7, server.requestCount)
            assertEquals("GET", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        }
    }

    private fun snapshot(): JSONObject {
        fun window(limit: Long) = JSONObject().put("limit", limit).put("used", 0).put("reserved", 0).put("remaining", limit).put("starts_at", 0).put("resets_at", 0)
        return JSONObject().put("enabled", true).put("eligible", true).put("server_time", 1_800_000_000)
            .put("models", JSONObject().put("text", listOf("workbench-free/deepseek-test")).put("image", "workbench-free/gpt-image-2"))
            .put("balance", JSONObject().put("five_hours", window(1_000_000)).put("seven_days", window(15_000_000)).put("generation", window(10))
                .put("day_timezone", "Asia/Shanghai").put("images", 0).put("maps", 0).put("pending_text", 0).put("pending_generation", 0).put("needs_review", false))
    }
}
