package tv.herrin.app
import org.junit.Assert.*
import org.junit.Test
class ConfigPairingSessionTest {
    @Test fun confirmationRequiredSingleUseAndIndependentIdleTimeout() {
        var now = 100L
        val s = ConfigPairingSession { now }
        assertEquals(64, s.pairing.length)
        assertFalse(s.request("wrong"))
        assertTrue(s.request(s.pairing))
        assertNull(s.claim(s.pairing))
        s.confirm()
        val session = s.claim(s.pairing)!!
        assertNull(s.claim(s.pairing))
        now += 180_001
        assertTrue(s.authorized(session))
        assertFalse(s.expired())
        now += 29 * 60_000
        assertTrue(s.authorized(session))
        now += 30 * 60_000
        assertFalse(s.authorized(session))
        assertTrue(s.expired())
    }
    @Test fun pairingTimeoutAndExplicitClose() {
        var now = 0L
        val s = ConfigPairingSession { now }
        now = 180_000
        assertFalse(s.request(s.pairing))
        assertTrue(s.expired())
        val t = ConfigPairingSession()
        t.request(t.pairing); t.confirm(); val key = t.claim(t.pairing)!!
        t.close(); assertFalse(t.authorized(key)); assertTrue(t.expired())
    }
}
