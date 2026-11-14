package io.github.zenthxsin.rustwright.core.account

import io.github.zenthxsin.rustwright.core.storage.InMemorySecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomRelayRouteTest {
    @Test fun normalizesBaseUrl() {
        assertEquals("https://api.example.com/v1", CustomRelayRoute.normalize(" https://api.example.com/ "))
        assertEquals("https://api.example.com/openai/v1", CustomRelayRoute.normalize("https://api.example.com/openai/v1/"))
        assertEquals("http://192.168.1.5:3000/v1", CustomRelayRoute.normalize("http://192.168.1.5:3000"))
    }

    @Test fun rejectsUnsafeUrls() {
        for (bad in listOf("", "ftp://x.com", "api.example.com", "https://x.com/v1\"", "https://x.com/v1' -c x", "https://u:p@x.com", "https://x.com/v1?a=1", "https:///v1"))
            assertFailsWith<IllegalArgumentException>(bad) { CustomRelayRoute.normalize(bad) }
    }

    @Test fun onlyActiveForTheAdminWhoEnabledIt() {
        var who: String? = "admin-1"
        val r = CustomRelayRoute(InMemorySecretStore()) { who }
        assertFailsWith<IllegalStateException> { r.setEnabled(true) }
        r.setBaseUrl("https://api.example.com"); r.setApiKey("sk-test-123456")
        r.setEnabled(true)
        assertEquals(CustomRelayRoute.Route("https://api.example.com/v1", "sk-test-123456"), r.active)
        who = "someone-else"; assertNull(r.active, "换号后失效")
        who = null; assertNull(r.active, "退出登录后失效")
        who = "admin-1"; assertTrue(r.active != null)
        r.revoke(); assertNull(r.active); assertFalse(r.enabled)
        assertEquals("sk-test-123456", r.apiKey, "关掉不清空填过的密钥")
        r.clear(); assertNull(r.apiKey); assertNull(r.baseUrl)
    }

    @Test fun keyIsMaskedAndValidated() {
        assertEquals("sk-…3456", CustomRelayRoute.mask("sk-test-123456"))
        assertFalse(CustomRelayRoute.Route("https://a/v1", "sk-test-123456").toString().contains("test"))
        val r = CustomRelayRoute(InMemorySecretStore()) { "a" }
        assertFailsWith<IllegalArgumentException> { r.setApiKey("sk 1") }
        assertFailsWith<IllegalArgumentException> { r.setApiKey("  ") }
    }
}
