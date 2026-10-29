package tv.herrin.app

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class MediaProxyPolicyTest {
    @Test fun onlyExplicitHttpOriginAndSafeHeadersAreAllowed() {
        val policy = MediaProxyPolicy.authorize("https://media.example/vod/master.m3u8?token=private", mapOf("Cookie" to "session=private"))
        assertEquals("https://media.example/vod/segment.ts", policy.resolve(policy.source, "segment.ts").toString())
        for (source in listOf("http://127.0.0.1/a", "http://[::1]/a", "file:///etc/passwd", "ftp://media.example/video", "http://u:p@media.example/video", "https://media.example/video#fragment")) {
            rejects { MediaProxyPolicy.authorize(source) }
        }
        for (ref in listOf("//evil.example/a", "http://media.example/a", "https://media.example:444/a", "http://127.0.0.1/a", "https://u:p@media.example/a", "segment.ts#x", "a\\b", "a\nb")) {
            rejects { policy.resolve(policy.source, ref) }
        }
        for (header in listOf("Host", "Range", "Connection", "Proxy-Authorization", "X-Forwarded-For")) {
            rejects { MediaProxyPolicy.authorize("https://media.example/a", mapOf(header to "bad")) }
        }
        rejects { MediaProxyPolicy.authorize("https://media.example/a", mapOf("Cookie" to "bad\r\nX: y")) }
        assertFalse(policy.toString().contains("private"))
    }

    @Test fun blocksPrivateReservedMappedAndTransitionAddresses() {
        val rejected = listOf("0.0.0.0", "10.1.2.3", "100.64.1.1", "127.0.0.1", "169.254.169.254", "172.16.0.1", "192.168.1.1", "192.0.0.1", "192.0.2.5", "192.88.99.1", "198.18.0.1", "198.51.100.2", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::", "::1", "::ffff:127.0.0.1", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1", "2001::1", "2002:7f00:1::", "3fff::1")
        rejected.forEach { assertFalse(it, MediaProxyPolicy.isPublicAddress(InetAddress.getByName(it))) }
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach {
            assertTrue(it, MediaProxyPolicy.isPublicAddress(InetAddress.getByName(it)))
        }
    }

    @Test fun actualDnsPathRejectsMixedAnswersAndRebinding() {
        var calls = 0
        val public = InetAddress.getByName("8.8.8.8")
        val private = InetAddress.getByName("127.0.0.1")
        val dns = MediaProxyPublicDns(object : Dns { override fun lookup(hostname: String) = if (calls++ == 0) listOf(public) else listOf(public, private) })
        assertEquals(listOf(public), dns.lookup("media.example"))
        try { dns.lookup("media.example"); fail("Rebinding must be rejected") } catch (_: UnknownHostException) { }
        assertEquals(2, calls)
    }

    @Test fun singleByteRangeIsBoundedAndValidated() {
        listOf("bytes=0-99", "bytes=100-", "bytes=-500").forEach { assertTrue(it, MediaLoopbackProxy.validRange(it)) }
        listOf("bytes=-0", "bytes=5-3", "bytes=-", "bytes=0-1,3-4", "bytes=999999999999999999999-", "Bytes=0-1", "bytes=0-1\r\nCookie:x").forEach {
            assertFalse(it, MediaLoopbackProxy.validRange(it))
        }
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Must reject unauthorized input") } catch (_: IllegalArgumentException) { }
    }
}
