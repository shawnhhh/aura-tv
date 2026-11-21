package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class LanJsonBodyTest {
    @Test fun chineseConnectionNameAndInternationalDomainRoundTripAsUtf8() {
        val text = """{"name":"我的阿里云盘","url":"http://www.饭太硬.net/tv"}"""
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, LanJsonBody.read(ByteArrayInputStream(bytes), bytes.size))
    }
    @Test(expected = IllegalArgumentException::class) fun truncatedBodyIsNeverSaved() {
        LanJsonBody.read(ByteArrayInputStream(byteArrayOf(123)), 3)
    }
}
