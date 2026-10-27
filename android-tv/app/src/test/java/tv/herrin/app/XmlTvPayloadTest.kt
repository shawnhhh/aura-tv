package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class XmlTvPayloadTest {
    private val document = """
        <tv><programme start="20261027120000 +0800" stop="20261027130000 +0800"
        channel="news"><title>中午新闻</title></programme></tv>
    """.trimIndent()

    private fun compress(value: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        GZIPOutputStream(bytes).use { it.write(value) }
    }.toByteArray()

    @Test fun plainAndGzipHaveExactlyTheSameSchedules() {
        val raw = document.toByteArray(Charsets.UTF_8)
        val plain = XmlTvPayload.read(ByteArrayInputStream(raw))
        val compressed = XmlTvPayload.read(ByteArrayInputStream(compress(raw)))
        assertEquals(plain, compressed)
        assertEquals("中午新闻", AuthorizedXmlTv.decode(compressed).single().title)
    }

    @Test fun compressedPayloadBeyondInflationLimitIsRejected() {
        val inflated = ("<tv>" + " ".repeat(AuthorizedXmlTv.MAX_BYTES) + "</tv>")
            .toByteArray(Charsets.UTF_8)
        assertTrue(runCatching {
            XmlTvPayload.read(ByteArrayInputStream(compress(inflated)))
        }.isFailure)
    }

    @Test fun oversizedTransportAndTruncatedGzipAreRejected() {
        assertTrue(runCatching {
            XmlTvPayload.read(ByteArrayInputStream(ByteArray(4_000_001)))
        }.isFailure)
        assertTrue(runCatching {
            XmlTvPayload.read(ByteArrayInputStream(byteArrayOf(0x1f, 0x8b.toByte(), 0x08)))
        }.isFailure)
    }

    @Test fun inflatedGuideStillForbidsXmlEntities() {
        val hostile = """<!DOCTYPE tv [<!ENTITY leak SYSTEM "file:///etc/passwd">]><tv/>"""
        assertTrue(runCatching {
            AuthorizedXmlTv.decode(XmlTvPayload.read(
                ByteArrayInputStream(compress(hostile.toByteArray(Charsets.UTF_8)))))
        }.isFailure)
    }
}
