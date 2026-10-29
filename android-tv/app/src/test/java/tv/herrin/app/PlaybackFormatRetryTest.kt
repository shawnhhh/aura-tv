package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackFormatRetryTest {
    @Test fun detectsDirectAndQueryEmbeddedFormats() {
        assertEquals(PlaybackFormatRetry.HLS, PlaybackFormatRetry.initialMime("https://media.test/film.m3u8?token=abc"))
        assertEquals(PlaybackFormatRetry.HLS, PlaybackFormatRetry.initialMime("https://media.test/play?source=film.m3u8"))
        assertEquals(PlaybackFormatRetry.DASH, PlaybackFormatRetry.initialMime("https://media.test/manifest.mpd"))
        assertNull(PlaybackFormatRetry.initialMime("https://media.test/play?id=abc"))
    }

    @Test fun extensionlessPlaylistTriesHlsThenDashOnlyForParsingErrors() {
        val uri = "https://media.test/vod?id=123"
        assertEquals(PlaybackFormatRetry.HLS, PlaybackFormatRetry.nextMime(uri, null, 3003))
        assertEquals(PlaybackFormatRetry.DASH, PlaybackFormatRetry.nextMime(uri, PlaybackFormatRetry.HLS, 3003))
        assertNull(PlaybackFormatRetry.nextMime(uri, PlaybackFormatRetry.DASH, 3003))
        assertNull(PlaybackFormatRetry.nextMime(uri, null, 2004))
        assertNull(PlaybackFormatRetry.nextMime("https://media.test/real.m3u8", null, 3003))
    }

    @Test fun fallbackCanUseAuthorizedBackupOnDecoderFailure() {
        assertTrue(PlaybackFormatRetry.retryNextSource(2004))
        assertTrue(PlaybackFormatRetry.retryNextSource(3003))
        assertTrue(PlaybackFormatRetry.retryNextSource(4001))
        assertFalse(PlaybackFormatRetry.retryNextSource(1001))
    }
}
