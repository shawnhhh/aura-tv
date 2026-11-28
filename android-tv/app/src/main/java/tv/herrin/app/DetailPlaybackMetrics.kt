package tv.herrin.app

/** Monotonic measurements; timestamps refer to CPU draws and actual renderer callbacks. */
internal class DetailPlaybackTimeline(private val clock: () -> Long, private val emit: (String, Long) -> Unit) {
    private var generation = 0L
    @Synchronized fun token(): Long = generation
    private var entry: Long? = null
    private var hit: Long? = null
    private var click: Long? = null
    private val recorded = hashSetOf<String>()
    @Synchronized fun enter() { generation++; entry = clock(); hit = null; click = null; recorded.clear() }
    private fun once(code: String, since: Long?) {
        if (since != null && recorded.add(code)) emit(code, (clock() - since).coerceAtLeast(0))
    }
    @Synchronized fun hit() { if (hit == null) { hit = clock(); once("ENTRY_TO_HIT", entry) } }
    @Synchronized fun details() = once("HIT_TO_DETAILS", hit)
    @Synchronized fun linesDrawn() = once("HIT_TO_LINE_CPU_DRAW", hit)
    @Synchronized fun episodesDrawn() = once("HIT_TO_EPISODE_CPU_DRAW", hit)
    @Synchronized fun click() { generation++; click = clock(); recorded.remove("CLICK_TO_MEDIA_READY"); recorded.remove("CLICK_TO_FIRST_FRAME") }
    @Synchronized fun mediaReady(token: Long = generation) { if (token == generation) once("CLICK_TO_MEDIA_READY", click) }
    @Synchronized fun firstFrame(token: Long = generation) { if (token == generation) once("CLICK_TO_FIRST_FRAME", click) }
}

internal object DetailPlaybackMetrics {
    val timeline = DetailPlaybackTimeline(android.os.SystemClock::elapsedRealtime) { code, ms ->
        HerrinPlaybackDiagnosticJournal.record("LOOKUP", code, "MS=${ms}")
    }
}
