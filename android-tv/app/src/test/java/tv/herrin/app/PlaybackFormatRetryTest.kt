package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackFormatRetryTest {
    @Test fun detectsPathFormatsWithoutTrustingQueryParameters() {
        assertEquals(PlaybackFormatRetry.HLS, PlaybackFormatRetry.initialMime("https://media.test/film.m3u8?token=abc"))
        assertNull(PlaybackFormatRetry.initialMime("https://media.test/play?source=film.m3u8"))
        assertEquals(PlaybackFormatRetry.DASH, PlaybackFormatRetry.initialMime("https://media.test/manifest.mpd"))
        assertNull(PlaybackFormatRetry.initialMime("https://media.test/play?id=abc"))
    }

    @Test fun sourceDeclaredManifestMimeIsNormalizedAndUntrustedMimeIsIgnored() {
        assertEquals(PlaybackFormatRetry.HLS, PlaybackFormatRetry.declaredMime("application/vnd.apple.mpegurl"))
        assertEquals(PlaybackFormatRetry.HLS, PlaybackFormatRetry.declaredMime("application/x-mpegURL; charset=utf-8"))
        assertEquals(PlaybackFormatRetry.HLS, PlaybackFormatRetry.declaredMime("m3u8"))
        assertEquals(PlaybackFormatRetry.DASH, PlaybackFormatRetry.declaredMime("APPLICATION/DASH+XML ; charset=utf-8"))
        assertNull(PlaybackFormatRetry.declaredMime("text/html"))
        assertNull(PlaybackFormatRetry.declaredMime("https://example.test/manifest.m3u8"))
        assertNull(PlaybackFormatRetry.declaredMime(null))
    }

    @Test fun sourceFormatAppliesOnlyToPrimaryUrlNotToDifferentBackup() {
        val primary = "https://video.example.test/stream?id=1"
        val backupMp4 = "https://cdn.example.test/fallback.mp4"
        val backupDash = "https://cdn.example.test/fallback.mpd"
        assertEquals(PlaybackFormatRetry.HLS,
            PlaybackFormatRetry.candidateMime(primary, 0, "application/vnd.apple.mpegurl"))
        assertNull(PlaybackFormatRetry.candidateMime(backupMp4, 1, PlaybackFormatRetry.HLS))
        assertEquals(PlaybackFormatRetry.DASH,
            PlaybackFormatRetry.candidateMime(backupDash, 1, PlaybackFormatRetry.HLS))
        assertEquals(PlaybackFormatRetry.HLS,
            PlaybackFormatRetry.candidateMime("https://cdn.example.test/backup.m3u8", 1,
                PlaybackFormatRetry.DASH))
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

    @Test fun sessionBoundsAttemptsAndDoesNotRestartAfterReadinessOrRepeatedErrors() {
        val session = PlaybackFormatRetry.Session("https://media.test/play", (1..20).map {
            "https://media.test/backup/$it"
        }, null)
        assertEquals(8, session.candidates.size)
        val attempts = mutableListOf(session.current)
        while (true) attempts.add(session.next(3003, null) ?: break)
        assertEquals(24, attempts.size) // Eight extensionless URLs: sniff, HLS, DASH.
        assertEquals(24, attempts.distinct().size)
        repeat(100) { assertNull(session.next(3003, null)) }
    }

    @Test fun nextAddressNeverInheritsPrimaryOrPriorAttemptMime() {
        val session = PlaybackFormatRetry.Session("https://media.test/primary", listOf(
            "https://media.test/backup.mp4", "https://media.test/backup.mpd"), "hls")
        assertEquals(PlaybackFormatRetry.HLS, session.current.mime)
        assertEquals(PlaybackFormatRetry.DASH, session.next(3003, null)?.mime)
        val mp4 = session.next(3003, null)!!
        assertEquals(1, mp4.candidateIndex)
        assertNull(mp4.mime)
        val dash = session.next(2004, 503)!!
        assertEquals(2, dash.candidateIndex)
        assertEquals(PlaybackFormatRetry.DASH, dash.mime)
        assertNull(session.next(4001, null))
    }

    @Test fun denialDoesNotProbeMimeAndRateLimitStopsAutomaticRequests() {
        val denied = PlaybackFormatRetry.Session("https://media.test/primary", listOf(
            "https://media.test/backup.mp4"), null)
        assertEquals(1, denied.next(3003, 403)?.candidateIndex)
        val limited = PlaybackFormatRetry.Session("https://media.test/primary", listOf(
            "https://media.test/backup.mp4"), null)
        assertNull(limited.next(2004, 429))
        assertNull(limited.next(2004, 503))
        assertFalse(PlaybackFormatRetry.retryNextSource(2006))
        assertFalse(PlaybackFormatRetry.retryNextSource(2007))
    }

    @Test fun declaredMp4AndDuplicateAddressesAreHandledWithoutExtraRequests() {
        assertEquals(PlaybackFormatRetry.MP4, PlaybackFormatRetry.declaredMime("video/mp4; codecs=avc1"))
        val uri = "https://media.test/play"
        val session = PlaybackFormatRetry.Session(uri, listOf(uri, uri), "mp4")
        assertEquals(1, session.candidates.size)
        assertEquals(PlaybackFormatRetry.MP4, session.current.mime)
        assertNull(session.next(3003, null))
    }

    @Test fun misleadingQueryCannotPinWrongMimeAndUserRetryStartsFreshBudget() {
        val uri = "https://media.test/play?label=old.m3u8&signature=abc.mpd"
        assertNull(PlaybackFormatRetry.initialMime(uri))
        val session = PlaybackFormatRetry.Session(uri, emptyList(), null)
        assertEquals(PlaybackFormatRetry.HLS, session.next(3003, null)?.mime)
        assertEquals(PlaybackFormatRetry.DASH, session.next(3003, null)?.mime)
        assertNull(session.next(3003, null))
        assertNull(session.restart().mime)
        assertEquals(PlaybackFormatRetry.HLS, session.next(3003, null)?.mime)
    }
}
