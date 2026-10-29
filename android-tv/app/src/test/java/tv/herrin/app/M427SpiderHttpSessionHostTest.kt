package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M427SpiderHttpSessionHostTest {
    @Test fun `host-only cookie follows path and secure transport but not other hosts`() {
        val session = SpiderHttpSessionHost()
        assertTrue(session.setCookie("https://media.example.test/login",
            "session=privateA; Path=/; Secure; HttpOnly"))
        assertEquals("session=privateA",
            session.getCookie("https://media.example.test/playlist.m3u8"))
        assertEquals("", session.getCookie("http://media.example.test/playlist.m3u8"))
        assertEquals("", session.getCookie("https://other.example.test/playlist.m3u8"))
        assertEquals("", session.getCookie("https://media.example.test.evil.test/"))
        session.clear()
        assertEquals("", session.getCookie("https://media.example.test/"))
    }

    @Test fun `path-scoped and replaced cookies do not leak or duplicate`() {
        val session = SpiderHttpSessionHost()
        assertTrue(session.setCookie("https://media.example.test/login", "token=old; Path=/private"))
        assertEquals("", session.getCookie("https://media.example.test/public/index.m3u8"))
        assertEquals("token=old", session.getCookie("https://media.example.test/private/part.ts"))
        assertTrue(session.setCookie("https://media.example.test/login", "token=new; Path=/private"))
        assertEquals("token=new", session.getCookie("https://media.example.test/private/part.ts"))
        assertTrue(session.setCookie("https://media.example.test/login",
            "token=gone; Path=/private; Max-Age=0"))
        assertEquals("", session.getCookie("https://media.example.test/private/part.ts"))
    }

    @Test fun `domain constraints prevent a site claiming an unrelated host`() {
        val session = SpiderHttpSessionHost()
        assertFalse(session.setCookie("https://media.example.test/", "id=bad; Domain=outside.test"))
        assertEquals("", session.getCookie("https://outside.test/"))
        assertTrue(session.setCookie("https://media.example.test/", "id=allowed; Domain=example.test"))
        assertEquals("id=allowed", session.getCookie("https://sub.example.test/"))
        assertEquals("", session.getCookie("https://unrelated.test/"))
    }

    @Test fun `cookie store has strict size and invalid input bounds`() {
        val session = SpiderHttpSessionHost()
        assertFalse(session.setCookie("https://media.example.test", "x=" + "a".repeat(4097)))
        assertFalse(session.setCookie("not a URL", "x=1"))
        assertEquals("", session.getCookie("not a URL"))
        repeat(160) { index ->
            assertTrue(session.setCookie("https://media.example.test/", "t$index=a; Path=/"))
        }
        val stored = session.getCookie("https://media.example.test/")
        assertFalse(stored.contains("t0=a"))
        assertTrue(stored.contains("t159=a"))
        assertTrue(stored.split("; ").size <= 128)
    }

    @Test fun `different cookie hosts are independent sessions`() {
        val first = SpiderHttpSessionHost()
        val second = SpiderHttpSessionHost()
        assertTrue(first.setCookie("https://media.example.test/", "a=one"))
        assertEquals("", second.getCookie("https://media.example.test/"))
        first.clear()
        assertEquals("", first.getCookie("https://media.example.test/"))
    }
}
