package io.github.zenthxsin.rustwright.core.account

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NewApiAccountTest {

    @Test
    fun formatsBalanceByDisplayType() {
        val usd = JSONObject().put("quota_per_unit", 500000).put("quota_display_type", "USD")
        assertEquals("$2.00", NewApiAccountRepository.formatBalance("1000000", usd))
        val cny = JSONObject().put("quota_per_unit", 500000).put("quota_display_type", "CNY").put("usd_exchange_rate", 7.2)
        assertEquals("¥14.40", NewApiAccountRepository.formatBalance("1000000", cny))
        val tokens = JSONObject().put("quota_per_unit", 500000).put("quota_display_type", "TOKENS")
        assertEquals("1000000 配额", NewApiAccountRepository.formatBalance("1000000", tokens))
    }

    @Test
    fun loopbackCallbackValidatesStateAndIssuer() {
        LoopbackOAuthCallback("http://127.0.0.1:18787/oauth/callback").use { cb ->
            val ok = cb.parse("/oauth/callback?code=abc&state=s1&iss=https%3A%2F%2Fx%2Foidc", "s1", "https://x/oidc")
            assertEquals("abc", ok["code"])
            assertFailsWith<IllegalStateException> { cb.parse("/oauth/callback?code=abc&state=bad&iss=https%3A%2F%2Fx%2Foidc", "s1", "https://x/oidc") }
            assertFailsWith<IllegalStateException> { cb.parse("/other?code=abc&state=s1&iss=https%3A%2F%2Fx%2Foidc", "s1", "https://x/oidc") }
            assertFailsWith<IllegalStateException> { cb.parse("/oauth/callback?code=a&code=b&state=s1&iss=https%3A%2F%2Fx%2Foidc", "s1", "https://x/oidc") }
        }
    }

    @Test
    fun configRejectsUnsafeRedirects() {
        assertFailsWith<IllegalArgumentException> { NewApiOAuthConfig(redirectUri = "http://evil.example/oauth/callback") }
        assertFailsWith<IllegalArgumentException> { NewApiOAuthConfig(issuer = "http://example.com/oidc") }
    }

    @Test
    fun planGates() {
        assertEquals(true, PaidFeature.LIVE_CANVAS.availableFor(SubscriptionPlan.PRO))
        assertEquals(false, PaidFeature.LIVE_CANVAS.availableFor(SubscriptionPlan.CREATOR))
        assertEquals(SubscriptionPlan.BASIC, SubscriptionPlan.fromId("unknown"))
    }
}
