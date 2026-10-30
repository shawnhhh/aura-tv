package tv.herrin.app

/**
 * D-pad always changes focus synchronously. Only background art is deferred.
 * This avoids full-screen bitmap decoding for intermediate carousel stops.
 */
internal object RemoteMotionPolicy {
    const val HERO_DWELL_MS = 180L
    const val HERO_CROSSFADE_MS = 180
    const val FOCUS_RETRY_FRAMES = 3
}
