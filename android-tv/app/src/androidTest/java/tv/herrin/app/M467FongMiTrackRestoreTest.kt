package tv.herrin.app

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fongmi.android.tv.bean.Track
import com.fongmi.android.tv.player.track.TrackUtil
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Upstream restoration contracts across real Media3 track models after a manifest rebuild. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class M467FongMiTrackRestoreTest {
    private fun format(id: String, language: String, label: String) = Format.Builder()
        .setId(id).setLanguage(language).setLabel(label).setSampleMimeType("text/vtt").build()

    private class SelectionHost(val tracks: Tracks) {
        var parameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) {
                _, method, args ->
            when (method.name) {
                "getCurrentTracks" -> tracks
                "getTrackSelectionParameters" -> parameters
                "setTrackSelectionParameters" -> { parameters = args!![0] as TrackSelectionParameters; null }
                "toString" -> "ManifestTrackSelectionHost"
                else -> throw AssertionError("Unexpected Player call: ${method.name}")
            }
        } as Player
    }

    private fun host(vararg formats: Format): SelectionHost {
        val group = TrackGroup("rebuilt", *formats)
        return SelectionHost(Tracks(listOf(Tracks.Group(group, false,
            IntArray(formats.size) { C.FORMAT_HANDLED }, BooleanArray(formats.size)))))
    }

    @Test fun languageAndLabelRestoreAfterTrackIdsAndOrdinalsChange() {
        val saved = TrackUtil.createTrack(C.TRACK_TYPE_TEXT, "English",
            format("old-id", "en", "English"), 0).apply { isSelected = true }
        val host = host(format("zh-new", "zh", "中文"), format("en-new", "en", "English"))
        val found = TrackUtil.findSelection(host.player, saved)!!
        assertEquals(listOf(1), found.trackIndices)
        assertTrue(TrackUtil.setTrackSelection(host.player, saved))
        assertEquals(listOf(1), host.parameters.overrides.values.single().trackIndices)
    }

    @Test fun missingLanguageStaysPendingAndDisabledTrackRestoresOffState() {
        val selected = TrackUtil.createTrack(C.TRACK_TYPE_TEXT, "English",
            format("old", "en", "English"), 0).apply { isSelected = true }
        val host = host(format("new", "zh", "中文"))
        assertFalse(TrackUtil.setTrackSelection(host.player, selected))
        assertEquals(listOf(selected), TrackUtil.setTrackSelection(host.player, listOf(selected)))
        assertTrue(host.parameters.overrides.isEmpty())
        selected.isSelected = false
        assertTrue(TrackUtil.setTrackSelection(host.player, selected))
        assertTrue(host.parameters.overrides.values.single().trackIndices.isEmpty())
        TrackUtil.reset(host.player)
        assertTrue(host.parameters.overrides.isEmpty())
        assertFalse(host.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
    }

    @Test fun selectionRoundTripPreservesFormatAndSecondaryRole() {
        val host = host(format("a", "zh", "中文"), format("b", "en", "English"))
        val group = host.tracks.groups.single().mediaTrackGroup
        val chosen = TrackUtil.fromSelection(host.tracks, TrackSelectionOverride(group, 1), Track.ROLE_SECONDARY)!!
        assertEquals(2, TrackUtil.count(host.tracks, C.TRACK_TYPE_TEXT))
        assertEquals(0, TrackUtil.count(host.tracks, C.TRACK_TYPE_AUDIO))
        assertEquals("en", chosen.language)
        assertEquals("English", chosen.label)
        assertEquals("text/vtt", chosen.mimeType)
        assertEquals(1, chosen.ordinal)
        assertTrue(chosen.isSecondary)
        assertEquals(TrackUtil.describeFormat(group.getFormat(1)), chosen.format)
        assertNull(TrackUtil.fromSelection(host.tracks, TrackSelectionOverride(group, listOf(0, 1)), Track.ROLE_PRIMARY))
    }
}
