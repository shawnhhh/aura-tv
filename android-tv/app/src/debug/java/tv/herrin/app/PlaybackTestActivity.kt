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
    val states = java.util.concurrent.CopyOnWriteArrayList<Int>()
    val cueTexts = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var showing by remember { mutableStateOf(true) }
            MaterialTheme {
                if (showing) AuthorizedPlaybackPanel(
                    PlaybackMedia(
                        intent.getStringExtra("fixture")!!,
                        subtitles = intent.getStringExtra("subtitle")?.let {
                            listOf(AuthorizedSubtitle("zh", "简体中文测试字幕", it))
                        }.orEmpty()
                    ),
                    onPlayer = {
                        player = it
                        it.addListener(object : androidx.media3.common.Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) { states.add(state) }
                            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                                cueTexts.clear()
                                cueTexts.addAll(cueGroup.cues.mapNotNull { cue -> cue.text?.toString() })
                            }
                        })
                    },
                    onBack = { showing = false }
                ) else Text("Playback released")
            }
        }
    }
}
