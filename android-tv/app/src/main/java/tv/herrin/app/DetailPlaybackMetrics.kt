package tv.herrin.app

/** Monotonic measurements; timestamps refer to CPU draws and actual renderer callbacks. */
internal class DetailPlaybackTimeline(private val clock: () -> Long, private val emit: (String, Long) -> Unit) {
    private var entry: Long? = null
    private var hit: Long? = null
    private var click: Long? = null
    private val recorded = hashSetOf<String>()
    fun enter() { entry = clock(); hit = null; click = null; recorded.clear() }
    private fun once(code: String, since: Long?) {
        if (since != null && recorded.add(code)) emit(code, (clock() - since).coerceAtLeast(0))
    }
    fun hit() { if (hit == null) { hit = clock(); once("ENTRY_TO_HIT", entry) } }
    fun details() = once("HIT_TO_DETAILS", hit)
    fun linesDrawn() = once("HIT_TO_LINE_CPU_DRAW", hit)
    fun episodesDrawn() = once("HIT_TO_EPISODE_CPU_DRAW", hit)
    fun click() { click = clock(); recorded.remove("CLICK_TO_MEDIA_READY"); recorded.remove("CLICK_TO_FIRST_FRAME") }
    fun mediaReady() = once("CLICK_TO_MEDIA_READY", click)
    fun firstFrame() = once("CLICK_TO_FIRST_FRAME", click)
}

internal object DetailPlaybackMetrics {
    val timeline = DetailPlaybackTimeline(android.os.SystemClock::elapsedRealtime) { code, ms ->
        HerrinPlaybackDiagnosticJournal.record("LOOKUP", code, "MS=${ms.coerceAtMost(99999)}")
    }
}
