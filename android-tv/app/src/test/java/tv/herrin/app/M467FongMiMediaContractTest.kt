package tv.herrin.app

import com.fongmi.android.tv.player.media.ConcatenatingUrl
import org.junit.Assert.*
import org.junit.Test

/** Crosses the real imported FongMi segment protocol, not a new host URL convention. */
class M467FongMiMediaContractTest {
    @Test fun concatenationPreservesSignedSourceAndDurationAndUtf8Length() {
        val first = "https://licensed.test/电影?token=opaque&part=1"
        val second = "https://licensed.test/part2"
        val source = "$first|||1234***$second|||5678"
        val segments = ConcatenatingUrl.parse(source)
        assertEquals(listOf(first, second), segments.map { it.url() })
        assertEquals(listOf(1234L, 5678L), segments.map { it.durationMs() })
        val edl = ConcatenatingUrl.toEdl(source)
        assertTrue(edl.startsWith("edl://%${first.toByteArray(Charsets.UTF_8).size}%$first,0,1.234;"))
        assertTrue(edl.endsWith("$second,0,5.678"))
    }
    @Test fun malformedSegmentsAreRejectedBeforeOpeningAnySource() {
        listOf("https://a|||0***https://b|||1", "https://a|||1***|||3",
            "https://a|||1***https://b|||9223372036854775807").forEach {
            assertThrows(IllegalArgumentException::class.java) { ConcatenatingUrl.parse(it) }
        }
        assertEquals("https://a.test/ordinary.mp4", ConcatenatingUrl.toEdl("https://a.test/ordinary.mp4"))
    }
    @Test fun subtitleFormatMatchesUpstreamAcrossSrtAssSsaVttAndUnknown() {
        assertEquals("text/vtt", FongMiSubtitleFormats.mimeType("a.VTT"))
        assertEquals("text/x-ssa", FongMiSubtitleFormats.mimeType("a.ass"))
        assertEquals("text/x-ssa", FongMiSubtitleFormats.mimeType("a.SSA"))
        assertEquals("application/x-subrip", FongMiSubtitleFormats.mimeType("a.srt"))
        assertEquals("application/x-subrip", FongMiSubtitleFormats.mimeType("/subtitle?id=7"))
        assertEquals("application/ttml+xml", FongMiSubtitleFormats.mimeType("a.dfxp"))
        assertEquals("", FongMiSubtitleFormats.mimeType(null))
    }
}
