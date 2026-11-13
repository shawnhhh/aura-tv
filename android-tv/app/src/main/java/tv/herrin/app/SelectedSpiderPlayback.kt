package tv.herrin.app

import tv.herrin.app.nativeplayback.FongMiSessionAuthorization
import tv.herrin.app.nativeplayback.NativeFongMiRoute

/** Explicit user selection, distinct from background candidate discovery.
 * FongMi hands the resolved stream to its player; a second media GET with a
 * shorter deadline can reject a still-running Spider callback and abandon it.
 * Authenticate the exact active local session here. Only the existing player
 * opens media bytes, owns cancellation and reports decoder success/failure.
 */
internal object SelectedSpiderPlayback {
    suspend fun admit(
        media: PlaybackMedia,
        site: String?,
        remoteProbe: suspend (PlaybackMedia) -> Boolean,
        evidence: (String) -> Unit = {}
    ): Boolean {
        if (!NativeFongMiRoute.isNativeProxy(media.uri)) return remoteProbe(media)
        val health = FongMiSessionAuthorization.inspect(media.uri, site)
        evidence("SELECTED_PROXY_HEALTH_${health.name}")
        if (health != NativeFongMiRoute.Health.READY) return false
        evidence("SELECTED_PROXY_MEDIA_DEFERRED_TO_PLAYER")
        return true
    }
}
