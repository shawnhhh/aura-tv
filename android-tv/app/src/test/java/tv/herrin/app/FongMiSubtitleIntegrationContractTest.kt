package tv.herrin.app

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class FongMiSubtitleIntegrationContractTest {
    @Test fun approvedSpiderSubtitleFormatsAndFlagsSurviveTheRealIpcCodec() {
        val result = SpiderVodCodec.player("""{"parse":0,"url":"https://original.invalid/movie.mp4","subs":[
          {"url":"https://original.invalid/en.srt","lang":"en","flag":2},
          {"url":"https://original.invalid/zh.ass","lang":"zh"},
          {"url":"https://original.invalid/other.ssa","lang":"en"},
          {"url":"https://original.invalid/caption?signature=private","lang":"en","format":"application/ttml+xml"},
          {"url":"https://original.invalid/a.vtt","lang":"en"}
        ]}""", false) as PlaybackResult.Ready
        assertEquals(listOf("application/x-subrip", "text/x-ssa", "text/x-ssa",
            "application/ttml+xml", "text/vtt"), result.media.subtitles.map { it.mimeType })
        assertEquals(C.SELECTION_FLAG_FORCED, result.media.subtitles[0].rawFlag)
        val decoded = SpiderIpc.readPlayback(SpiderIpc.playback(result.media), false) as PlaybackResult.Ready
        assertEquals(result.media, decoded.media)
    }

    @Test fun explicitUpstreamFlagsOverrideLanguageDefaults() {
        val flags = FongMiPlaybackItemSupport.subtitleSelectionFlags(listOf("en", "zh", "ja"),
            rawFlags = listOf(C.SELECTION_FLAG_FORCED, 0, C.SELECTION_FLAG_DEFAULT))
        assertEquals(listOf(C.SELECTION_FLAG_FORCED, C.SELECTION_FLAG_AUTOSELECT,
            C.SELECTION_FLAG_DEFAULT), flags)
        assertEquals(listOf(C.SELECTION_FLAG_DEFAULT),
            FongMiPlaybackItemSupport.subtitleSelectionFlags(listOf("en")))
    }

    @Test fun subtitleExpansionStillRejectsUntrustedSchemesAndUnknownDecoders() {
        val result = SpiderVodCodec.player("""{"parse":0,"url":"https://original.invalid/movie.mp4","subs":[
          {"url":"file:///private.srt","lang":"en"},
          {"url":"javascript:private.ass","lang":"en"},
          {"url":"https://user:secret@original.invalid/a.srt","lang":"en"},
          {"url":"https://original.invalid/a.srt","lang":"en","format":"text/html"},
          {"url":"https://original.invalid/page","lang":"en"}
        ]}""", false) as PlaybackResult.Ready
        assertTrue(result.media.subtitles.isEmpty())
        assertEquals(7, FongMiSubtitleFormats.selectionFlag(255))
    }
}
