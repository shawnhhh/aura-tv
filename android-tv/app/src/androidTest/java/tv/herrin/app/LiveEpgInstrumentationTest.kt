package tv.herrin.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class LiveEpgInstrumentationTest {
    @Test fun acceptsHttpsGzipGuideAndRejectsBadPayloadWithoutEscapingSource() {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val open: (String) -> HttpURLConnection = { endpoint ->
            (URL(endpoint).openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = clientTls.sslSocketFactory()
            }
        }
        val repository = RemoteLiveEpgRepository(open, timeoutMs = 1000)
        val address = server.url("/my-guide.xml.gz").toString()
        val xml = """
            <tv><programme channel="licensed" start="20261027180000 +0800"
            stop="20261027190000 +0800"><title>授权节目</title></programme></tv>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val gzip = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(xml) }
        }.toByteArray()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/x-gzip")
                .setBody(Buffer().write(gzip)))
            val loaded = runBlocking { repository.load(address) } as LiveEpgResult.Ready
            assertEquals("授权节目", loaded.programmes.single().title)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/gzip")
                .setBody(Buffer().write(byteArrayOf(0x1f, 0x8b.toByte(), 0x08))))
            assertTrue(runBlocking { repository.load(address) } is LiveEpgResult.Error)
            server.enqueue(MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/another-guide.xml.gz").toString()))
            assertTrue(runBlocking { repository.load(address) } is LiveEpgResult.Error)
            assertTrue(runBlocking {
                repository.load("http://localhost/my-guide.xml.gz")
            } is LiveEpgResult.Error)
        } finally {
            server.shutdown()
        }
    }

    @Test fun androidXmlParserIgnoresExternalDtdAndParsesOriginalGuide() {
        val data = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv>
              <programme channel="channel-one" start="20261026100000 +0000" stop="20261026110000 +0000">
                <title>HERRIN original guide fixture</title>
              </programme>
            </tv>
        """.trimIndent()
        val programmes = AuthorizedXmlTv.decode(data)
        assertEquals(1, programmes.size)
        assertEquals("channel-one", programmes.single().channelId)
    }

    @Test fun androidXmlParserRejectsInlineEntitiesAndNonstandardDoctypes() {
        val entity = """<!DOCTYPE tv [<!ENTITY leak SYSTEM "file:///etc/passwd">]><tv><programme channel="x" start="20261026100000 +0000" stop="20261026110000 +0000"><title>&leak;</title></programme></tv>"""
        val unexpectedDtd = """<!DOCTYPE tv SYSTEM "https://example.org/guide.dtd"><tv/>"""
        assertTrue(runCatching { AuthorizedXmlTv.decode(entity) }.isFailure)
        assertTrue(runCatching { AuthorizedXmlTv.decode(unexpectedDtd) }.isFailure)
    }
}
