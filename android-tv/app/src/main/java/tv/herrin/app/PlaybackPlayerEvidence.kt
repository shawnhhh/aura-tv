package tv.herrin.app

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

// Optional Exo evidence only. MPV first-frame evidence comes from the original
// kernel listener; absence of Exo counters is never fabricated as decoded frames.
internal val Player.videoDecoderCounters get() = (this as? ExoPlayer)?.videoDecoderCounters
internal val Player.videoFormat get() = (this as? ExoPlayer)?.videoFormat
    ?: currentTracks.groups.asSequence().filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
        .flatMap { group -> (0 until group.length).asSequence().filter(group::isTrackSelected).map(group::getTrackFormat) }
        .firstOrNull()
internal val Player.isReleased get() = (this as? ExoPlayer)?.isReleased ?: false
