package tv.herrin.app

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class PersonalWebDavTransportTest {
    @Test fun httpAndHttpsPropfindKeepMetadataAndRangeAuthenticationAtTheConfiguredOrigin() = runBlocking {
        for(tls in listOf(false, true)) MockWebServer().use { server ->
            val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            if(tls) server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            server.start()
            val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .followRedirects(false).followSslRedirects(false).build()
            server.enqueue(MockResponse().setResponseCode(207).setBody("""
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/Example.2024.mkv</d:href>
                <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>5000000000</d:getcontentlength>
                <d:getlastmodified>Mon, 01 Jan 2024 00:00:00 GMT</d:getlastmodified></d:prop></d:propstat>
                </d:response><d:response><d:href>http://foreign.invalid/file.mkv</d:href></d:response></d:multistatus>
            """.trimIndent()))
            val source = HerrinWebDavSource(HerrinWebDavAccount("Fixture", server.url("/dav/").toString(), "fixture", "secret"), client)
            val listed = source.list(server.url("/dav/").toString())
            assertEquals(1, listed.size); assertEquals(5_000_000_000L, listed.single().size)
            val propfind = server.takeRequest()
            assertEquals("PROPFIND", propfind.method); assertEquals("1", propfind.getHeader("Depth"))
            assertNotNull(propfind.getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/5000000000").setHeader("Content-Type", "video/x-matroska").setBody("x"))
            assertTrue(HerrinPersonalMediaProbe(client).canRead(PlaybackMedia(listed.single().address,
                mapOf("Authorization" to okhttp3.Credentials.basic("fixture", "secret")))))
            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
            MockWebServer().use { other ->
                other.start()
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/stolen")))
                assertFalse(HerrinPersonalMediaProbe(client).canRead(PlaybackMedia(server.url("/dav/file.mkv").toString(),
                    mapOf("Authorization" to "Basic private"))))
                assertEquals(0, other.requestCount)
            }
        }
    }
}
