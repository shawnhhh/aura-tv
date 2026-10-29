package tv.herrin.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DanmakuTest {
    private val original get() = javaClass.getResource("/danmaku/original.xml")!!.readText()
    private val on = DanmakuSettings(enabled = true)
    @Test fun fongmiUrlAndArrayAndEncodedArrayAreExplicitSources() {
        val source = "https://sidecar.example.test/original.xml?session=private"
        assertEquals(source, DanmakuProtocol.decodeSources(source).single().url)
        val array = JSONArray().put(JSONObject().put("name", "原创").put("url", source))
        assertEquals(DanmakuProtocol.decodeSources(array), DanmakuProtocol.decodeSources(array.toString()))
        assertEquals(DanmakuProtocol.decodeSources(array),
            DanmakuProtocol.decodeSources(DanmakuProtocol.encodeSources(DanmakuProtocol.decodeSources(array))))
        assertFalse(DanmakuProtocol.decodeSources(source).single().toString().contains("private"))
    }
    @Test fun invalidOrUnknownServiceSchemesNeverBecomeSources() {
        listOf("http://example.test/a.xml", "file:///private", "javascript:alert(1)",
            "live://plugin/id", "https://user:secret@example.test/a.xml", "https://127.0.0.1/a",
            "https://localhost/a", "[invalid", "").forEach {
            assertTrue("rejected $it", DanmakuProtocol.decodeSources(it).isEmpty())
        }
    }
    @Test fun originalXmlDecodesSecondsModesColorsAndEntities() {
        val comments = DanmakuProtocol.parseXml(original)
        assertEquals(listOf(0L, 1500L, 10000L, 20000L), comments.map { it.timeMs })
        assertEquals(listOf(1, 5, 4, 6), comments.map { it.mode })
        assertEquals(0x00ffff, comments[2].color)
        assertEquals("反向滚动 & 安全实体", comments.last().text)
    }
    @Test fun malformedRowsAreSkippedAndOrderingIsDeterministic() {
        val xml = """<i><d p="2,1,25,1">two</d><d p="NaN,1,25,1">bad</d>
          <d p="1,1,25,1">one</d><d p="1,1,25,1">one</d><d p="0,9,25,1">unsupported</d>
          <d p="0,1,NaN,1">bad</d><d p="0,1,25,-1">bad</d></i>"""
        assertEquals(listOf("one", "two"), DanmakuProtocol.parseXml(xml).map { it.text })
    }
    @Test fun doctypeAndExternalEntitiesAreRejectedWithoutResolution() {
        val payloads = listOf("<!DOCTYPE i SYSTEM 'https://example.test/evil'><i/>",
            "<!DOCTYPE i [<!ENTITY leak SYSTEM 'file:///private'>]><i><d p='0,1,25,1'>&leak;</d></i>")
        payloads.forEach { payload ->
            try { DanmakuProtocol.parseXml(payload); fail("DTD must be rejected") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun disabledRepositoryMakesZeroRequestsAndFailuresCannotEscapeToPlayback() {
        var requests = 0
        val repo = DanmakuRepository { requests++; error("simulated unavailable sidecar") }
        val source = DanmakuSource("原创", "https://example.test/original.xml")
        assertTrue(repo.load(source, false).isEmpty())
        assertEquals(0, requests)
        assertTrue(repo.load(source, true).isEmpty())
        assertEquals(1, requests)
        val valid = DanmakuRepository { original }
        assertEquals(4, valid.load(source, true).size)
    }
    @Test fun pauseUsesSameVideoPositionAndSeekDropsStaleAndFutureComments() {
        val data = DanmakuProtocol.parseXml(original)
        val playing = DanmakuTimeline.visible(data, 2000, on)
        assertEquals(playing, DanmakuTimeline.visible(data, 2000, on))
        val sought = DanmakuTimeline.visible(data, 10500, on)
        assertEquals(listOf("快进后按影片时间出现"), sought.map { it.comment.text })
        assertEquals(listOf("HERRIN 原创星海测试"),
            DanmakuTimeline.visible(data, 500, on).map { it.comment.text })
        assertTrue(DanmakuTimeline.visible(data, 500, DanmakuSettings()).isEmpty())
        assertTrue(DanmakuTimeline.visible(emptyList(), 10500, on).isEmpty()) // new episode/line
    }
    @Test fun speedChangesDurationAndSettingsAreBounded() {
        val data = listOf(DanmakuComment(0, "原创"))
        assertEquals(1, DanmakuTimeline.visible(data, 6000, on).size)
        assertEquals(0, DanmakuTimeline.visible(data, 6000, on.copy(speed = 2f)).size)
        val bounded = DanmakuSettings(true, Float.NaN, 500f, -1f).normalized()
        assertEquals(.75f, bounded.opacity, .001f)
        assertEquals(2f, bounded.speed, .001f)
        assertEquals(16f, bounded.fontSize, .001f)
    }
    @Test fun boundedParserRejectsOversizeInputAndCapsCrowdedFrames() {
        try { DanmakuProtocol.parseXml("x".repeat(DanmakuProtocol.MAX_BYTES + 1)); fail() }
        catch (_: IllegalArgumentException) { }
        val crowded = (0 until 2000).map { DanmakuComment(0, "原创 $it") }
        assertTrue(DanmakuTimeline.visible(crowded, 1, on).size <= 24)
    }
}
