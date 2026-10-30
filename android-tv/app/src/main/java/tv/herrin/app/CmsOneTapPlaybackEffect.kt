package tv.herrin.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.withTimeoutOrNull

/**
 * M4.54: isolated player-first CMS detail pipeline.
 *
 * Do not inline this coroutine-heavy body into the giant Herrin() Compose
 * function. It had crossed the 64KiB JVM method limit, causing
 * MainActivityKt.Herrin MethodTooLargeException in both Android CI jobs.
 *
 * The current callbacks are read via rememberUpdatedState, so a late detail
 * response merges with the latest screen/metadata rather than an older
 * providerFilms snapshot. Leaving playback or switching film cancels the
 * LaunchedEffect before it may publish a stale result.
 */
@Composable
internal fun CmsOneTapPlaybackEffect(
    screen: String,
    film: Film,
    site: TvBoxSite?,
    spiderActive: Boolean,
    pendingFilmId: String?,
    retryEpoch: Int,
    allowHttp: Boolean,
    repository: TvBoxVodRepository,
    onReady: (resolvedFilm: Film, target: Pair<Int, String>) -> Unit,
    onFailure: (filmId: String) -> Unit
) {
    val latestReady by rememberUpdatedState(onReady)
    val latestFailure by rememberUpdatedState(onFailure)
    LaunchedEffect(screen, film.id, site?.apiUrl, spiderActive,
        pendingFilmId, retryEpoch, allowHttp) {
        if (screen != "player" || spiderActive || site == null ||
            pendingFilmId != film.id || film.cmsVodId.isNullOrBlank()) return@LaunchedEffect

        // Already-resolved media in the same selected Film does not need a
        // second HTTP detail fetch. Only the selected source's HTTPS tracks
        // can be given to the licensed player.
        CmsOneTapPlaybackPolicy.firstPlayable(film)?.let {
            latestReady(film, it)
            return@LaunchedEffect
        }
        val loaded = withTimeoutOrNull(8_000L) {
            repository.details(site, film, allowHttp)
        }
        val resolved = (loaded as? TvBoxVodDetailResult.Ready)?.film
            ?.takeIf { it.id == film.id }
            ?.let { FilmMetadataEnrichment.mergeSourceDetails(film, it) }
        val chosen = resolved?.let(CmsOneTapPlaybackPolicy::firstPlayable)
        if (chosen != null && resolved != null) {
            latestReady(resolved, chosen)
        } else {
            latestFailure(film.id)
        }
    }
}
