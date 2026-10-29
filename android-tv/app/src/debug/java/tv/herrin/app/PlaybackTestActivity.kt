package tv.herrin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.media3.exoplayer.ExoPlayer

/** Debug-only host. Local test media is never accepted by production settings. */
class PlaybackTestActivity : ComponentActivity() {
    lateinit var player: ExoPlayer
    val createdPlayers = java.util.concurrent.CopyOnWriteArrayList<ExoPlayer>()
    val states = java.util.concurrent.CopyOnWriteArrayList<Int>()
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
                if (showing) AuthorizedPlaybackPanel(
                    intent.getStringExtra("spiderJson")?.let { json ->
                        // Debug-only acceptance path: use the same strict Spider
                        // result decoder/headers as production, no plugin/JAR execution.
                        (SpiderVodCodec.player(json, allowHttp = false)
                            as? PlaybackResult.Ready)?.media
                            ?: error("Debug Spider fixture was not a licensed direct stream")
                    } ?: PlaybackMedia(
                        intent.getStringExtra("fixture")
                            ?: error("Debug playback fixture not configured"),
                        subtitles = intent.getStringExtra("subtitle")?.let {
                            listOf(AuthorizedSubtitle("zh", "简体中文测试字幕", it))
                        }.orEmpty(),
                        alternateUris = intent.getStringExtra("alternate")?.let { listOf(it) }.orEmpty()
                    ),
                    onPlayer = {
                        player = it
                        createdPlayers.add(it)
                        it.addListener(object : androidx.media3.common.Player.Listener {
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
                    },
                    onBack = {
                        android.util.Log.i("HerrinPlayerBack", "debug onBack invoked showing=$showing")
                        showing = false
                    }
                ) else Text("Playback released")
            }
        }
    }
}
