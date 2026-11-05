package tv.herrin.app

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class FongMiPlayerManagerIntegrationTest {
    @Test fun productionAdapterOwnsMediaIdentityHeadersControlsAndRelease() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val fixture = File(inst.targetContext.cacheDir, "manager-original-${java.util.UUID.randomUUID()}.mp4")
        inst.context.assets.open("synthetic.mp4").use { input ->
            fixture.outputStream().use { output -> input.copyTo(output) }
        }
        try {
            inst.runOnMainSync {
                val adapter = HerrinPlaybackAdapter(inst.targetContext, PlaybackMedia(
                    Uri.fromFile(fixture).toString(), mapOf("X-Fixture" to "original"),
                    declaredMime = "video/mp4", mediaId = "original-film/episode-1/main"))
                val player = adapter.player
                try {
                    adapter.start()
                    val item = player.currentMediaItem!!
                    assertEquals("original-film/episode-1/main", item.mediaId)
                    assertEquals("original", item.requestMetadata.extras!!.getString("X-Fixture"))
                    assertEquals("video/mp4", item.localConfiguration!!.mimeType)
                    adapter.pause()
                    assertFalse(player.playWhenReady)
                    adapter.play()
                    assertTrue(player.playWhenReady)
                    adapter.seekTo(500)
                    assertEquals(500L, player.currentPosition)
                    adapter.start(position = 250)
                    assertEquals(250L, player.currentPosition)
                    assertEquals(2L, adapter.manager.playbackAttempt)
                } finally { adapter.close(); adapter.close() }
                assertTrue(player.isReleased)
                assertThrows(IllegalStateException::class.java) { adapter.play() }
            }
        } finally { fixture.delete() }
    }
}
