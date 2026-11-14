package io.github.zenthxsin.rustwright.core.runtime.net

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EndpointsTest {
    @Test fun defaultsAreReservedAndUnconfigured() {
        assertTrue(java.net.URI(Endpoints.main()).host.endsWith(".invalid"))
        assertEquals(listOf(Endpoints.main()), Endpoints.relayOrigins())
        assertEquals("", Endpoints.signer())
    }

    @Test fun configuredAddressesRejectEmbeddedCredentials() {
        val name = "workbench.api.origin"
        val previous = System.getProperty(name)
        try {
            System.setProperty(name, "https://user:secret@example.invalid")
            assertFailsWith<IllegalArgumentException> { Endpoints.main() }
            System.setProperty(name, "https://api.other.invalid/")
            assertEquals("https://api.other.invalid", Endpoints.main())
        } finally { if (previous == null) System.clearProperty(name) else System.setProperty(name, previous) }
    }

    @Test fun whitelistRejectsOtherHosts() {
        val fallback = RelayNodes.origin + "/v1"
        assertEquals(fallback, RelayNodes.trusted("https://evil.example.com/v1"))
        assertEquals(fallback, RelayNodes.trusted(null))
        assertEquals(fallback, RelayNodes.trusted("http://api.example.invalid/v1"))
        assertEquals(Endpoints.main() + "/v1", RelayNodes.trusted(Endpoints.main() + "/v1/"))
    }
}
