package tv.herrin.app

import androidx.compose.runtime.*
import java.util.concurrent.atomic.AtomicLong

/** One remembered observable holder keeps the large screen's generated DEX
 * below the problematic register pressure. No credentials or saved UI change. */
@Stable
internal class TmdbScreenState {
    var sourceForeground by mutableStateOf(false)
    var sourceSearchJob:kotlinx.coroutines.Job?=null
    var tmdbHomeFilms by mutableStateOf<List<Film>>(emptyList())
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
    var tmdbSourceStatus by mutableStateOf("")
    var tmdbStatusFilm by mutableStateOf("")
    var tmdbStageStarted by mutableLongStateOf(0L)
    var tmdbSearchRunning by mutableStateOf(false)
    var tmdbExtendedRequest by mutableStateOf<String?>(null)
    val tmdbSearchPlans=SourceSearchPlans()
    val tmdbSearchGeneration=AtomicLong()
    var tmdbLookupEpoch by mutableIntStateOf(0)
}
