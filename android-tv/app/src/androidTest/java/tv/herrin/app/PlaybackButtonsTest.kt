package tv.herrin.app

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Makes sure bottom-row focusable controls are actually clickable with TV D-pad ENTER. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlaybackButtonsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun until(message: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 16_000
        while (System.currentTimeMillis() < end) {
            if (predicate()) return
            Thread.sleep(90)
        }
        fail(message)
    }
    private fun remoteClick(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsFocused()
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    @Test fun dpadCenterActivatesNativeSeekAudioAndSubtitleControls() {
        val ctx = inst.targetContext
        val movie = File(ctx.cacheDir, "m23-player-buttons.mp4")
        inst.context.assets.open("synthetic.mp4").use { input ->
            movie.outputStream().use { output -> input.copyTo(output) }
        }
        val caption = File(ctx.cacheDir, "m23-buttons-caption.vtt")
        inst.context.assets.open("synthetic.vtt").use { input ->
            caption.outputStream().use { output -> input.copyTo(output) }
        }
        ActivityScenario.launch<PlaybackTestActivity>(
            Intent(ctx, PlaybackTestActivity::class.java)
                .putExtra("fixture", movie.toURI().toString())
                .putExtra("subtitle", caption.toURI().toString())
        ).use { scenario ->
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithTag("transport-forward").assertIsDisplayed() }.isSuccess
            }
            until("Media3 must reach real decoded state") {
                var decoded = 0
                scenario.onActivity { decoded = it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0 }
                decoded > 3
            }
            remoteClick("player-verification-toggle")
            compose.waitUntil(8_000) {
                runCatching { compose.onNodeWithTag("player-verification-status")
                    .assertTextContains("已输出视频帧：", substring = true) }.isSuccess
            }
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
            until("Pause media before seeking") {
                var paused = false
                scenario.onActivity { paused = !it.player.playWhenReady }
                paused
            }
            remoteClick("transport-forward")
            until("D-pad CENTER on +10s button must seek native player") {
                var moved = false
                scenario.onActivity { moved = it.player.duration > 0 &&
                    it.player.currentPosition >= it.player.duration - 500 }
                moved
            }
            remoteClick("transport-rewind")
            until("D-pad CENTER on -10s button must seek near start") {
                var rewound = false
                scenario.onActivity { rewound = it.player.currentPosition <= 500 }
                rewound
            }
            compose.onNodeWithTag("transport-audio").assertIsEnabled()
            var oldAudio = ""
            scenario.onActivity { oldAudio = PlaybackTransport.currentLabel(it.player, androidx.media3.common.C.TRACK_TYPE_AUDIO) }
            remoteClick("transport-audio")
            until("D-pad CENTER must actually change audio track") {
                var changed = false
                scenario.onActivity {
                    changed = PlaybackTransport.currentLabel(it.player, androidx.media3.common.C.TRACK_TYPE_AUDIO) != oldAudio
                }
                changed
            }
            compose.onNodeWithTag("transport-subtitle").assertIsEnabled()
            var before = ""
            scenario.onActivity { before = PlaybackTransport.currentLabel(it.player, androidx.media3.common.C.TRACK_TYPE_TEXT) }
            remoteClick("transport-subtitle")
            until("D-pad CENTER must actually toggle subtitle track") {
                var changed = false
                scenario.onActivity {
                    changed = PlaybackTransport.currentLabel(it.player, androidx.media3.common.C.TRACK_TYPE_TEXT) != before
                }
                changed
            }
            compose.onNodeWithTag("transport-speed").assertIsDisplayed()
            remoteClick("transport-speed")
            until("D-pad CENTER must change Media3 playback speed") {
                var adjusted = false
                scenario.onActivity { adjusted = kotlin.math.abs(it.player.playbackParameters.speed - 1.25f) < 0.01f }
                adjusted
            }
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            // The real remote Back callback already fired. This test owns Compose's
            // virtual frame clock: advance pending recomposition so the screen's
            // DisposableEffect can release Media3 before asserting release.
            // Do not replace Back with performClick or remove the release assertion.
            compose.waitForIdle()
            until("Back must release player after using UI") {
                var released = false
                scenario.onActivity { released = it.player.isReleased }
                released
            }
        }
    }
}
