package tv.herrin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only MPV playback entrypoint. Does NOT appear on Herrin home screen.
 * Invoked only by native-enabled instrumentation using an authorized fixture.
 */
class MpvNativeSmokeActivity : ComponentActivity() {
    private var nativeView: MpvNativePlaybackView? = null
    val loaded = AtomicBoolean(false)
    @Volatile var failure: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixture = intent.getStringExtra("mpvFixtureUrl")
            ?: error("Debug MPV fixture URL is required")
        check(MpvNativeAvailability.inspect(this).available) {
            "The native candidate libraries have not been packaged"
        }
        val player = MpvNativePlaybackView(this)
        nativeView = player
        setContentView(player)
        player.start(fixture, mapOf("X-Herrin-MPV-Fixture" to "synthetic"), object : MpvNativePlaybackView.Listener {
            override fun onLoaded() { loaded.set(true) }
            override fun onFailure(reason: Int, code: Int) {
                failure = "MPV engine failure reason=$reason code=$code"
            }
        })
    }

    override fun onDestroy() {
        nativeView?.close()
        nativeView = null
        super.onDestroy()
    }

    internal fun nativeSurface(): MpvNativePlaybackView? = nativeView
}
