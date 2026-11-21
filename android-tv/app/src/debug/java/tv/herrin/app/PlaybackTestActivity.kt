package tv.herrin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.media3.exoplayer.ExoPlayer

/** Debug-only host. Local test media is never accepted by production settings. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackTestActivity : ComponentActivity() {
    lateinit var player: ExoPlayer
    var nextEpisodeRequests = 0
    var lineSelections = 0
    var episodeSelections = 0
    var otherSourceSelections = 0
    data class ProgressEvent(val sessionUri: String, val positionMs: Long,
                             val durationMs: Long, val finished: Boolean)
    val progressEvents = java.util.concurrent.CopyOnWriteArrayList<ProgressEvent>()
    private var inPlaceFixture by mutableStateOf<String?>(null)

    /** Exercise the same composition slot across episodes instead of recreating the Activity. */
    fun switchFixtureInPlace(url: String) { inPlaceFixture = url }
    val createdPlayers = java.util.concurrent.CopyOnWriteArrayList<ExoPlayer>()
    val states = java.util.concurrent.CopyOnWriteArrayList<Int>()
    val firstFramePlayers = java.util.concurrent.CopyOnWriteArrayList<ExoPlayer>()
    private val videoCounters = java.util.IdentityHashMap<ExoPlayer,
        java.util.concurrent.CopyOnWriteArrayList<androidx.media3.exoplayer.DecoderCounters>>()
    /** Prewarming/repeat can replace ExoPlayer's single counters reference
     * before the active renderer stops. Count actual output from every observed
     * renderer belonging to this player, never from a previous player session.
     */
    fun renderedVideoFrames(): Int = videoCounters[player].orEmpty().sumOf {
        it.ensureUpdated()
        it.renderedOutputBufferCount
    }
    val cueTexts = java.util.concurrent.CopyOnWriteArrayList<String>()
    // Retain a short history: the final empty cue event on ENDED must not erase
    // evidence that Media3 really decoded a WebVTT caption earlier in the clip.
    val cueHistory = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** Mimic leaving one channel and opening the next in a fresh player route. */
    fun switchFixture(url: String) {
        intent.putExtra("fixture", url)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var showing by remember { mutableStateOf(true) }
            MaterialTheme {
                if (showing) {
                    val media = intent.getStringExtra("spiderJson")?.let { json ->
                        // Debug-only acceptance path: use the same strict Spider
                        // result decoder/headers as production, no plugin/JAR execution.
                        (SpiderVodCodec.player(json,
                            allowHttp = intent.getBooleanExtra("spiderAllowHttp", false))
                            as? PlaybackResult.Ready)?.media
                            ?: error("Debug Spider fixture was not a licensed direct stream")
                    } ?: PlaybackMedia(
                        inPlaceFixture ?: intent.getStringExtra("fixture")
                            ?: error("Debug playback fixture not configured"),
                        // Test-only source credentials: prove they reach Media3 on
                        // original local fixtures, never production player settings.
                        headers = intent.getStringExtra("fixtureAuthorization")
                            ?.let { mapOf("Authorization" to it) }.orEmpty(),
                        subtitles = intent.getStringExtra("subtitle")?.let {
                            listOf(AuthorizedSubtitle("zh", "简体中文测试字幕", it))
                        }.orEmpty(),
                        alternateUris = intent.getStringExtra("alternate")?.let { listOf(it) }.orEmpty(),
                        declaredMime = intent.getStringExtra("fixtureMime")
                    )
                    val sessionUri = media.uri
                    AuthorizedPlaybackPanel(
                        media = media,
                        onNextEpisode = if (intent.getBooleanExtra("continuousFixture", false)) ({ nextEpisodeRequests++ }) else null,
                        onPlaybackProgress = { position, duration, finished ->
                            progressEvents.add(ProgressEvent(sessionUri, position, duration, finished))
                        },
                        onPlayer = {
                            player = it
                            if (createdPlayers.addIfAbsent(it)) {
                            // The committed callback can run again after UI updates.
                            // Observe each actual player once and retain its real
                            // state at attachment, including initial BUFFERING.
                            states.add(it.playbackState)
                            val owner = it
                            val counters = java.util.concurrent.CopyOnWriteArrayList<androidx.media3.exoplayer.DecoderCounters>()
                            videoCounters[owner] = counters
                            owner.videoDecoderCounters?.let(counters::addIfAbsent)
                            owner.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                                override fun onVideoEnabled(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                                    decoderCounters: androidx.media3.exoplayer.DecoderCounters) {
                                    counters.addIfAbsent(decoderCounters)
                                }
                            })
                            it.addListener(object : androidx.media3.common.Player.Listener {
                                override fun onRenderedFirstFrame() { firstFramePlayers.addIfAbsent(it) }
                                override fun onPlaybackStateChanged(state: Int) { states.add(state) }
                                override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                                    cueTexts.clear()
                                    cueTexts.addAll(cueGroup.cues.mapNotNull { cue -> cue.text?.toString() })
                                    if (cueTexts.isNotEmpty()) {
                                        cueHistory.addAll(cueTexts)
                                        android.util.Log.i("HerrinCaptions",
                                            "decodedWebVttCues=" + cueTexts.toList())
                                    }
                                }
                            })
                            }
                        },
                        onLineSelection = if (intent.getBooleanExtra("withSelectionActions", false))
                            ({ lineSelections++ }) else null,
                        onEpisodeSelection = if (intent.getBooleanExtra("withSelectionActions", false))
                            ({ episodeSelections++ }) else null,
                        onTryOtherSource = if (intent.getBooleanExtra("withOtherSourceAction", false))
                            ({ otherSourceSelections++ }) else null,
                        onBack = {
                            android.util.Log.i("HerrinPlayerBack", "debug onBack invoked showing=$showing")
                            showing = false
                        },
                        // Diagnostics are available solely in this isolated
                        // debug/test fixture, never in the real HERRIN player.
                        testDiagnostics = !intent.getBooleanExtra("consumerControls", false)
                    )
                } else Text("Playback released")
            }
        }
    }
}
