package tv.herrin.app

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player

/** Immutable facts sampled when the existing player emits a state/track event.
 * Passing these values across the Compose boundary prevents stale chrome when
 * playback pauses at an unchanged position and the child would otherwise skip. */
internal data class PlayerControlState(
    val speed: Float, val videoFormat: Format?,
    val audio: List<PlaybackTransport.Option>, val subtitles: List<PlaybackTransport.Option>
) {
    companion object {
        @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
        fun read(player: Player) = PlayerControlState(player.playbackParameters.speed,
            player.videoFormat,PlaybackTransport.options(player,C.TRACK_TYPE_AUDIO),
            PlaybackTransport.options(player,C.TRACK_TYPE_TEXT))
    }
}
