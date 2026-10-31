package tv.herrin.app

import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * OPT-IN PHYSICAL ARM TV test. Excluded from normal Android TV instrumentation.
 *
 * Explicitly run only on a TV authorized to use the chosen source:
 * - loads the current live FanTaiYing configuration, not a fake CMS fixture;
 * - executes the selected csp_* plugin inside HERRIN's separate :spider process;
 * - fetches one film's real details and provider-declared playback address;
 * - requires Media3 to render at least one actual decoded video frame.
 *
 * No credentials/media URLs are logged or uploaded. The default site "YGP"
 * advertises trailers. Override with -e siteKey <configured site key>.
 * The chosen source may be unavailable, require login, or not authorize play.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(markerClass = [UnstableApi::class])
class FanTaiYingArmLivePlaybackTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext

    private fun stage(label: String) {
        Log.i("HERRIN-LIVE-E2E", "stage=$label")
    }

    private fun failure(label: String, reason: String): Nothing {
        val redacted = reason
            .replace(Regex("(?i)https?://[^\\s<>]+"), "[地址已隐藏]")
            .replace(Regex("(?i)(token|cookie|authorization|password)\\s*[:=]\\s*\\S+"),
                "$1=[已隐藏]")
            .take(150)
        throw AssertionError("stage=$label reason=$redacted")
    }

    @Test
    fun actualArmSourceSpiderEpisodeProducesMedia3VideoFrame() {
        // The provider JAR ships ARM guards, not a compatible x86 library.
        // Failing early here avoids misreporting an x86 emulator as TCL evidence.
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        assertTrue("This live plugin requires a physical ARM TV; device ABI=$abi",
            abi.startsWith("arm64") || abi.startsWith("armeabi"))
        val siteKey = inst.arguments.getString("siteKey")?.takeIf { it.isNotBlank() } ?: "YGP"

        stage("config")
        val loaded = runBlocking {
            withTimeout(45_000) {
                TvBoxConfigRepository(context).load("http://www.饭太硬.net/tv", allowHttp = true)
            }
        }
        val warehouse = loaded as? TvBoxConfig.Single
            ?: failure("config", if (loaded is TvBoxConfig.Error) loaded.reason
                       else "not a single TVBox config")
        val site = warehouse.sites.firstOrNull {
            it.key == siteKey && it.spiderApi != null
        } ?: failure("site", "no configured Spider site for selected key")
        stage("spider_browse")
        val bridge = TvBoxSpiderBridge(context)
        try {
            val listed = runBlocking {
                withTimeout(75_000) { bridge.browse(site, warehouse) }
            }
            val films = when (listed) {
                is SpiderResult.Ready -> listed.value
                is SpiderResult.Failure -> failure("spider_browse", listed.message)
            }
            val firstFilm = films.firstOrNull { it.spiderVodId != null }
                ?: failure("catalog", "source returned no selectable video")
            stage("spider_details")
            val detailResult = runBlocking {
                withTimeout(55_000) { bridge.details(firstFilm) }
            }
            val detail = when (detailResult) {
                is SpiderResult.Ready -> detailResult.value
                is SpiderResult.Failure -> failure("spider_details", detailResult.message)
            }
            val episode = detail.playbackEpisodes.firstOrNull { it.lines.isNotEmpty() }
                ?: failure("episodes", "provider did not return a selectable episode")
            stage("spider_player")
            val selected = runBlocking {
                withTimeout(55_000) {
                    bridge.play(detail, episode.number, episode.lines.first().id, allowHttp = true)
                }
            }
            val media = when (selected) {
                is PlaybackResult.Ready -> selected.media
                is PlaybackResult.Unavailable -> failure("spider_player", selected.reason)
            }
            stage("media3_first_video_frame")
            verifyDecodedVideoFrame(media)
            stage("PASS_real_decoded_video_frame")
        } finally {
            bridge.close()
        }
    }

    /**
     * Exercise the actual player transport/decoder, not an HTTP status check.
     * A tiny native Surface is necessary to make Media3 render decoded frames.
     */
    private fun verifyDecodedVideoFrame(media: PlaybackMedia) {
        val decoded = CountDownLatch(1)
        val playbackError = AtomicReference<String?>(null)
        val surfaceTexture = SurfaceTexture(0).apply { setDefaultBufferSize(640, 360) }
        val surface = Surface(surfaceTexture)
        var player: ExoPlayer? = null
        try {
            inst.runOnMainSync {
                val http = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(media.allowHttpRedirects)
                    .setConnectTimeoutMs(12_000)
                    .setReadTimeoutMs(12_000)
                    .setDefaultRequestProperties(media.headers)
                val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
                val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
                val exo = ExoPlayer.Builder(context, renderers)
                    .setMediaSourceFactory(factory).build()
                player = exo
                exo.setVideoSurface(surface)
                exo.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { decoded.countDown() }
                    override fun onPlayerError(error: PlaybackException) {
                        playbackError.set(error.errorCodeName)
                        decoded.countDown()
                    }
                })
                val index = MediaItem.Builder().setUri(media.uri)
                val mime = PlaybackFormatRetry.declaredMime(media.declaredMime)
                    ?: PlaybackFormatRetry.initialMime(media.uri)
                if (mime != null) index.setMimeType(mime)
                exo.setMediaItem(index.build())
                exo.prepare()
                exo.playWhenReady = true
            }
            val signaled = decoded.await(45, TimeUnit.SECONDS)
            val error = playbackError.get()
            if (!signaled) failure("media3", "no video frame within 45 seconds")
            if (error != null) failure("media3", "Media3 error code=$error")
            var rendered = 0
            inst.runOnMainSync {
                rendered = player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0
            }
            assertTrue("Media3 reported first frame but decoder counters stayed empty",
                rendered > 0)
        } finally {
            inst.runOnMainSync {
                player?.release()
                player = null
            }
            surface.release()
            surfaceTexture.release()
        }
    }
}
