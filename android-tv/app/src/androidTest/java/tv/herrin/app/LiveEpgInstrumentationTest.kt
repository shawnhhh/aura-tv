package tv.herrin.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveEpgInstrumentationTest {
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
