package tv.herrin.app

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride

/** Focusable Android TV controls call these operations; never infer a stream URL. */
object PlaybackTransport {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    fun nextSpeed(current: Float): Float {
        val currentIndex = speeds.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        return speeds[(currentIndex + 1).coerceAtLeast(0) % speeds.size]
    }
    data class Option(
        val groupIndex: Int, val trackIndex: Int, val label: String,
        val selected: Boolean
    )

    fun seekBy(player: Player, deltaMs: Long): Long {
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            return player.currentPosition.coerceAtLeast(0L)
        }
        val current = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration
        val proposed = if (deltaMs >= 0L) {
            if (current > Long.MAX_VALUE - deltaMs) Long.MAX_VALUE else current + deltaMs
        } else (current + deltaMs).coerceAtLeast(0L)
        val limit = if (duration == C.TIME_UNSET || duration <= 0L) Long.MAX_VALUE else duration
        val target = proposed.coerceIn(0L, limit)
        player.seekTo(target)
        return target
    }

    fun options(player: Player, trackType: Int): List<Option> {
        if (!player.isCommandAvailable(Player.COMMAND_GET_TRACKS)) return emptyList()
        return player.currentTracks.groups.flatMapIndexed { groupIndex, group ->
            if (group.type != trackType) emptyList() else {
                (0 until group.length).mapNotNull { trackIndex ->
                    if (!group.isTrackSupported(trackIndex)) null else {
                        val format = group.getTrackFormat(trackIndex)
                        val language = format.language?.takeUnless { it == "und" || it.isBlank() }
                        Option(groupIndex, trackIndex,
                            format.label?.takeIf { it.isNotBlank() }
                                ?: language ?: "音轨 " + (trackIndex + 1),
                            group.isTrackSelected(trackIndex))
                    }
                }
            }
        }
    }

    fun cycle(player: Player, trackType: Int): Boolean {
        if (!player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return false
        val choices = options(player, trackType)
        if (choices.isEmpty()) return false
        if (trackType == C.TRACK_TYPE_AUDIO && choices.size < 2) return false
        val current = choices.indexOfFirst { it.selected }
        val next = current + 1
        if (trackType == C.TRACK_TYPE_TEXT && next >= choices.size) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return true
        }
        val choice = choices[next % choices.size]
        val group = player.currentTracks.groups[choice.groupIndex]
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(choice.trackIndex)))
            .build()
        return true
    }

    fun currentLabel(player: Player, type: Int): String {
        val tracks = options(player, type)
        return tracks.firstOrNull { it.selected }?.label
            ?: if (type == C.TRACK_TYPE_TEXT) "关闭" else "默认"
    }
}
