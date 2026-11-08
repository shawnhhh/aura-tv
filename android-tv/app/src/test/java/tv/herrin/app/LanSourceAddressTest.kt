package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class LanSourceAddressTest {
    @Test fun warehouseNormalizationMatchesTvBoxAndOtherModulesStayHttps() {
        for (raw in listOf("http://饭太硬.net/tv", "https://饭太硬.net/tv", "HTTP://example.org/a")) {
            assertEquals(TvBoxUrl.normalize(raw, true), AdditionalServiceConfigs.sourceAddress(raw))
        }
        for (raw in listOf("file:///a", "https://user:pass@example.org/a", "https://example.org/a#b",
            "http://example.org:0/a", "http://example.org/" + "a".repeat(2048), "http://example.org/a\nb")) {
            assertThrows(IllegalArgumentException::class.java) { AdditionalServiceConfigs.sourceAddress(raw) }
        }
        assertThrows(IllegalArgumentException::class.java) { AdditionalServiceConfigs.checkedAddress("http://example.org/a") }
    }

    @Test fun absoluteHttpSessionDeadlineCannotBeExtended() {
        var now = 0L
        val pairing = ConfigPairingSession(maxLifetimeMs = 300_000) { now }
        assertTrue(pairing.request(pairing.pairing))
        pairing.confirm()
        val grant = requireNotNull(pairing.claim(pairing.pairing))
        now = 299_999
        assertTrue(pairing.authorized(grant))
        now = 300_000
        assertTrue(pairing.expired())
        assertFalse(pairing.authorized(grant))
    }
}
