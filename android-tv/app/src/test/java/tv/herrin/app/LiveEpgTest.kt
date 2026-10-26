package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class LiveEpgTest {
    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv>
          <programme start="20261026180000 +0800" stop="20261026190000 +0800" channel="news">
            <title lang="zh">晚间新闻</title>
          </programme>
          <programme start="20261026190000 +0800" stop="20261026200000 +0800" channel="news">
            <title>天气预报</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test fun xmlTvMatchesChannelAndTimezoneWithoutFetchingDtd() {
        val items = AuthorizedXmlTv.decode(xml)
        assertEquals(2, items.size)
        val now = java.time.Instant.parse("2026-10-26T10:20:00Z").toEpochMilli()
        assertEquals("晚间新闻", AuthorizedXmlTv.current(items, "news", now)?.title)
        assertNull(AuthorizedXmlTv.current(items, "sports", now))
    }

    @Test fun forbidsEntitiesMalformedTimesAndEmptyProgrammes() {
        assertTrue(runCatching { AuthorizedXmlTv.decode(
            """<!DOCTYPE tv [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><tv><programme title="&xxe;"/></tv>"""
        ) }.isFailure)
        assertTrue(runCatching { AuthorizedXmlTv.decode(
            "<tv><programme channel=\"x\" start=\"bad\" stop=\"bad\"><title>Invalid</title></programme></tv>"
        ) }.isFailure)
    }
}
