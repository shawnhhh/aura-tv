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

    @Test fun nowAndNextIgnoreXmlOrderAndTrackProgressAtBoundaries() {
        val base = java.time.Instant.parse("2026-10-26T10:00:00Z").toEpochMilli()
        val entries = listOf(
            LiveProgramme("news", "第三档", base + 7_200_000, base + 10_800_000),
            LiveProgramme("sports", "体育", base, base + 3_600_000),
            LiveProgramme("news", "第二档", base + 3_600_000, base + 7_200_000),
            LiveProgramme("news", "第一档", base, base + 3_600_000)
        )
        val middle = AuthorizedXmlTv.snapshots(entries, base + 1_800_000)
        assertEquals("第一档", middle["news"]?.now?.title)
        assertEquals("第二档", middle["news"]?.next?.title)
        assertEquals(0.5f, middle["news"]?.progress ?: -1f, 0.001f)
        assertEquals("体育", middle["sports"]?.now?.title)
        val boundary = AuthorizedXmlTv.snapshots(entries, base + 3_600_000)
        assertEquals("第二档", boundary["news"]?.now?.title)
        assertEquals(0f, boundary["news"]?.progress ?: -1f, 0.001f)
        assertEquals("第三档", boundary["news"]?.next?.title)
        assertNull(boundary["sports"]?.now)
    }

    @Test fun futureOnlyAndMissingChannelNeverInventCurrentProgrammes() {
        val start = java.time.Instant.parse("2026-10-26T11:00:00Z").toEpochMilli()
        val schedules = AuthorizedXmlTv.snapshots(
            listOf(LiveProgramme("culture", "即将开始", start, start + 3_600_000)), start - 60_000)
        assertNull(schedules["culture"]?.now)
        assertEquals("即将开始", schedules["culture"]?.next?.title)
        assertNull(schedules["culture"]?.progress)
        assertNull(schedules["missing"])
    }
}
