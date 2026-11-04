/*
 * Derived from FongMi/TV player/engine/PlayerEngine.java and
 * player/exo/ExoPlayerEngine.java at 305dff233cde512e2c83f71529b7c3ecfd61fefc.
 * Original author: FongMi. GPL-3.0-only; android-tv/LICENSE-GPL-3.0.md.
 * 2026-11-04: official Media3 only; explicit Context and per-attempt source factory.
 * This is the Exo start/setMediaItem contract, not the fork-dependent full engine.
 */
package tv.herrin.app.nativeplayback.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import tv.herrin.app.FongMiExoPlayerFactory
import tv.herrin.app.PlaybackMedia

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class ExoPlayerEngine(private val context: Context, media: PlaybackMedia) {
    val player = FongMiExoPlayerFactory.create(context, media)

    fun start(spec: PlaySpec, startPositionMs: Long, playWhenReady: Boolean) {
        val source = FongMiExoPlayerFactory.mediaSourceFactory(context, spec.media)
            .createMediaSource(MediaItemFactory.from(spec))
        // C.TIME_UNSET means the manifest's default position, including live edge.
        player.setMediaSource(source, if (startPositionMs == C.TIME_UNSET) C.TIME_UNSET
            else startPositionMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    fun stop() = player.stop()
    fun release() = player.release()
}
