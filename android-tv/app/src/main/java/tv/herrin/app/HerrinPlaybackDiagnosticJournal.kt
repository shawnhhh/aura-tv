package tv.herrin.app

import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque

/**
 * Process-local bounded event ring. Only fixed host-defined codes and integers
 * may enter this journal; do not pass URLs, source names, cookies or exceptions.
 */
internal object HerrinPlaybackDiagnosticJournal {
    private val events = ArrayDeque<String>(256)
    private val stageCodes = setOf("LOOKUP", "SOURCE", "SPIDER", "PROXY", "MEDIA3", "RECOVERY", "CONFIG")
    private val eventCode = Regex("[A-Z][A-Z0-9_]{1,63}")
    private val detailCode = Regex("[A-Z][A-Z0-9_]{1,31}(?:=[0-9]{1,5})?")
    @Synchronized fun record(stage: String, event: String, detail: String = "", session: String? = PlaybackDiagnosticSession.id()) {
        if (stage !in stageCodes || !eventCode.matches(event)) return
        val safe = detail.takeIf { it.isNotBlank() && detailCode.matches(it) }
        val safeSession = session?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
        val line = "T+${SystemClock.elapsedRealtime()}ms " + (safeSession?.let { "SID=$it " } ?: "") + "$stage $event" +
            (safe?.let { " $it" } ?: "")
        if (events.size == 256) events.removeFirst()
        events.addLast(line)
        Log.i("HerrinPlaybackDiag", line)
    }
    @Synchronized fun export(): String =
        "HERRIN PLAYBACK DIAGNOSTICS (SAFE / NO TOKENS)\n" +
            events.joinToString("\n").ifBlank { "NO_EVENTS" }
    @Synchronized fun clear() { events.clear() }
}
