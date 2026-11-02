package tv.herrin.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class M468VerifiedFallbackTest {
    private val identity = HerrinFilmIdentity(157336, "movie",
        listOf("Interstellar", "星际穿越"), 2014)

    private fun movie(id: String, source: String) =
        Film(id, "Interstellar", "", "电影", "2014", "", R.drawable.photofilm_orbit,
            cmsVodId = if (source == "cms") id else null)

    private fun source(id: String, type: HerrinSourceType, offers: List<HerrinSourceMovie>,
                       calls: MutableList<String>) = object : HerrinFilmSource {
        override val id = id
        override val type = type
        override suspend fun search(identity: HerrinFilmIdentity): List<HerrinSourceMovie> {
            calls.add(id)
            return offers
        }
    }

    @Test fun unusablePersonalFileActuallyFallsBackToUsableTvboxDetail() = runBlocking {
        val calls = mutableListOf<String>()
        val nasOffer = HerrinSourceMovie("nas", HerrinSourceType.NAS,
            movie("nas-1", "nas"), "movie", resolutionClaim = 2160)
        val cmsOffer = HerrinSourceMovie("cms", HerrinSourceType.TVBOX_CMS,
            movie("cms-1", "cms"), "movie", resolutionClaim = 1080)
        val playable = cmsOffer.film.copy(playbackEpisodes =
            listOf(PlaybackEpisode(1, "正片", listOf(
                PlaybackLine("cms-line", "1080p", "https://fixture.invalid/movie.mp4")))))
        val result = HerrinVerifiedSourceResolver(listOf(
            source("nas", HerrinSourceType.NAS, listOf(nasOffer), calls),
            source("cms", HerrinSourceType.TVBOX_CMS, listOf(cmsOffer), calls)
        )).find(identity,
            personalPlayback = { PlaybackResult.Unavailable("WebDAV permission denied") },
            remoteDetails = { if (it == cmsOffer) playable else null })
        assertEquals(listOf("nas", "cms"), calls)
        assertEquals(HerrinSourceTier.TVBOX, result.lookup.selectedTier)
        assertEquals(cmsOffer, result.lookup.options.single())
        assertEquals(playable, result.sourceDetails)
        assertNull(result.personalPlaylist)
    }

    @Test fun aBadTvboxDetailDoesNotBlockTheNextMatchingCandidate() = runBlocking {
        val calls = mutableListOf<String>()
        val empty = HerrinSourceMovie("cms", HerrinSourceType.TVBOX_CMS,
            movie("missing", "cms"), "movie", resolutionClaim = 2160)
        val working = HerrinSourceMovie("cms", HerrinSourceType.TVBOX_CMS,
            movie("available", "cms"), "movie", resolutionClaim = 1080)
        val resolutionCalls = mutableListOf<String>()
        val result = HerrinVerifiedSourceResolver(listOf(
            source("cms", HerrinSourceType.TVBOX_CMS, listOf(empty, working), calls)
        )).find(identity, { PlaybackResult.Unavailable("none") }) {
            resolutionCalls.add(it.film.id)
            if (it.film.id == "available") it.film.copy(playbackEpisodes =
                listOf(PlaybackEpisode(1, "正片", listOf(
                    PlaybackLine("ok", "ready", "https://fixture.invalid/ok.mp4")))))
            else it.film
        }
        assertEquals(listOf("missing", "available"), resolutionCalls)
        assertEquals(working, result.lookup.options.single())
        assertEquals(1, result.sourceDetails!!.playbackEpisodes.size)
    }

    @Test fun validNasIsStrictlyPreferredAndPersonalEpisodeLinesAreReal() = runBlocking {
        val calls = mutableListOf<String>()
        val nas = HerrinSourceMovie("nas", HerrinSourceType.NAS,
            movie("nas-1", "nas"), "movie", resolutionClaim = 1080)
        val tvbox = HerrinSourceMovie("cms", HerrinSourceType.TVBOX_CMS,
            movie("cms-2", "cms"), "movie", resolutionClaim = 2160)
        val url = "https://licensed.invalid/movie.mp4"
        val result = HerrinVerifiedSourceResolver(listOf(
            source("nas", HerrinSourceType.NAS, listOf(nas), calls),
            source("cms", HerrinSourceType.TVBOX_CMS, listOf(tvbox), calls)
        )).find(identity, { PlaybackResult.Ready(PlaybackMedia(url)) }) {
            error("TVBox details should never be opened when a usable NAS file exists")
        }
        assertEquals(listOf("nas"), calls)
        assertEquals(HerrinSourceTier.PERSONAL, result.lookup.selectedTier)
        assertEquals(url, result.personalPlaylist!!.mediaByLine.values.single().uri)
    }
}
