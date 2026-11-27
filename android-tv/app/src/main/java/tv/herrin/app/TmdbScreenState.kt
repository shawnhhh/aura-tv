package tv.herrin.app

import androidx.compose.runtime.*
import java.util.concurrent.atomic.AtomicLong

/** One remembered observable holder keeps the large screen's generated DEX
 * below the problematic register pressure. No credentials or saved UI change. */
@Stable
internal class TmdbScreenState {
    val sourceDiscoveries = mutableMapOf<String, HerrinSourceDiscovery>()
    val selectedDiscoveryNode = mutableStateMapOf<String, String>()
    val autoLayoutSelected = mutableMapOf<String, String>()
    val discoveredSources = mutableStateMapOf<String, HerrinSourceDiscovery.Snapshot>()
    val discoverySpiderLock = kotlinx.coroutines.sync.Mutex()
    var discoverySelection by mutableStateOf<Pair<String, String>?>(null)
    var directPlayFilm: String? = null
    var discoveryRecovery: HerrinAutomaticPlayback.Search? = null
    val rejectedDiscoveryHits = mutableSetOf<String>()
    fun nextDiscoveryHit(id: String, failed: String? = null): HerrinSourceDiscovery.Hit? {
        failed?.let { rejectedDiscoveryHits += it }
        if (rejectedDiscoveryHits.size >= 12) return null
        val hits = discoveredSources[id]?.hits.orEmpty().filter {
            it.relevance == SourceRelevance.CONFIRMED && it.key !in rejectedDiscoveryHits
        }
        return hits.firstOrNull { it.episodes.isNotEmpty() } ?: hits.firstOrNull()
    }
    val requestedDiscoveryHits = mutableStateMapOf<String, String>()
    val loadedDiscoveryHits = mutableStateMapOf<String, String>()
    var discoveryLoadingFilm by mutableStateOf<String?>(null)
    var discoveryLoadingEpoch: Int = 0
    fun sourceSelectionReady(id: String): Boolean = discoveryLoadingFilm != id &&
        (requestedDiscoveryHits[id] == null || requestedDiscoveryHits[id] == loadedDiscoveryHits[id])
    /** Retire only the failed current selection's UI/media projection, not sibling layouts. */
    fun invalidateDiscoverySelection(id: String, hitKey: String) {
        if (requestedDiscoveryHits[id] != hitKey) return
        loadedDiscoveryHits.remove(id)
        tmdbResolved[id]?.let { old ->
            tmdbResolved[id] = old.copy(playbackEpisodes = emptyList(),
                cmsVodId = null, spiderVodId = null, spiderOrigin = null)
        }
        tmdbPersonalPlayback.remove(id)
        tmdbPlaybackRoutes.forget(id)
    }
    private var discoveryPlayRequest: Triple<String, String, Int>? = null
    var discoveryRequestedEpisode: Int? = null
        private set
    fun requestDiscoveryPlay(film: String, hit: String, episode: Int? = null) {
        discoveryRequestedEpisode = episode
        discoveryPlayRequest = Triple(film, hit, discoverySelectionEpoch)
    }
    fun hasDiscoveryPlay(film: String, hit: String, epoch: Int): Boolean =
        discoveryPlayRequest == Triple(film, hit, epoch)

    fun consumeDiscoveryPlay(film: String, hit: String, epoch: Int): Boolean {
        if (discoveryPlayRequest != Triple(film, hit, epoch)) return false
        discoveryPlayRequest = null
        discoveryRequestedEpisode = null
        return true
    }
    fun discardDiscoveryPlay(film: String, hit: String, epoch: Int) {
        consumeDiscoveryPlay(film, hit, epoch)
    }
    var discoveryLine: String? by mutableStateOf(null)
    var discoverySelectionEpoch by mutableIntStateOf(0)
    fun discoveryFor(id: String): HerrinSourceDiscovery {
        sourceDiscoveries[id]?.let { return it }
        if(sourceDiscoveries.size >= 24) {
            sourceDiscoveries.keys.first().let { old -> sourceDiscoveries.remove(old); discoveredSources.remove(old)
                selectedDiscoveryNode.remove(old); requestedDiscoveryHits.remove(old); loadedDiscoveryHits.remove(old)
                autoLayoutSelected.remove(old)
                tmdbResolved[old]?.let { tmdbResolved[old] = it.copy(playbackEpisodes = emptyList(), cmsVodId = null, spiderVodId = null) }
                tmdbPersonalPlayback.remove(old); tmdbPlaybackRoutes.forget(old) }
        }
        return HerrinSourceDiscovery().also { sourceDiscoveries[id] = it }
    }
    var resumeSourceFilm: String? = null
    var searchReturnFocus:String?=null
    var restoreSearchFocus by mutableStateOf(false)
    val globalSearch = TmdbGlobalSearchState()
    var sourceForeground by mutableStateOf(false)
    var sourceSearchJob:kotlinx.coroutines.Job?=null
    var tmdbBrowseIndex by mutableStateOf<List<Film>>(emptyList())
    var cacheHydrated by mutableStateOf(false)
    var tmdbTop250Films by mutableStateOf<List<Film>>(emptyList())
    var tmdbTop250Status by mutableStateOf("")
    var tmdbTop250RetryEpoch by mutableIntStateOf(0)
    val tmdbResolved=mutableStateMapOf<String,Film>()
    val tmdbPersonalPlayback=mutableStateMapOf<String,Map<String,PlaybackMedia>>()
    val tmdbPlaybackRoutes=HerrinTmdbPlaybackRouteStore()
    val tmdbFallbackIndex=HerrinTmdbFallbackIndex()
    val automaticPlayback=HerrinAutomaticPlayback()
    var automaticSearch by mutableStateOf<HerrinAutomaticPlayback.Search?>(null)
    var automaticResume by mutableStateOf<HerrinAutomaticPlayback.Resume?>(null)
    val tmdbSpiderBatchOffsets=mutableStateMapOf<String,Int>()
    val tmdbSpiderBatchCompleted=mutableStateMapOf<String,Int>()
    var tmdbDiscoveryStatus by mutableStateOf("")
    var preferredSiteStatus by mutableStateOf("")
    var tmdbSourceStatus by mutableStateOf("")
    var tmdbStatusFilm by mutableStateOf("")
    var tmdbStageStarted by mutableLongStateOf(0L)
    var tmdbSearchStarted by mutableLongStateOf(0L)
    var tmdbSearchRunning by mutableStateOf(false)
    var tmdbExtendedRequest by mutableStateOf<String?>(null)
    val tmdbSearchPlans=SourceSearchPlans()
    val tmdbSearchGeneration=AtomicLong()
    var tmdbLookupEpoch by mutableIntStateOf(0)

    /** Atomically leave public-source work before the existing private-file playback effect starts. */
    fun selectPersonal(entry: PersonalMediaPlaybackEntry.Selection) {
        tmdbSearchGeneration.incrementAndGet()
        sourceSearchJob?.cancel()
        sourceSearchJob = null
        discoverySelection = null
        discoveryPlayRequest = null
        discoveryRequestedEpisode = null
        discoverySelectionEpoch++
        discoveryLoadingEpoch++
        discoveryLoadingFilm = null
        discoveryLine = null
        discoveryRecovery = null
        directPlayFilm = null
        resumeSourceFilm = null
        automaticSearch = null
        automaticResume = null
        automaticPlayback.clear()
        // No transient/signed URL from an earlier private attempt is reused.
        tmdbPersonalPlayback.remove(entry.film.id)
        tmdbResolved[entry.film.id] = entry.film
        tmdbPlaybackRoutes.personal(entry.film.id)
    }
}
