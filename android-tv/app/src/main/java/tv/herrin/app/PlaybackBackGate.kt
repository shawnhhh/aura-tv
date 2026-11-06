package tv.herrin.app

/** Compose, PlayerView and the system dispatcher share one physical Back transition. */
internal class PlaybackBackGate {
    private var key: Long? = null
    private var at: Long? = null
    fun accept(downTime: Long?, now: Long): Boolean {
        if (downTime != null && downTime == key) return false
        if (downTime == null && at?.let { now - it in 0..100 } == true) return false
        if (downTime != null) key = downTime
        at = now
        return true
    }
}
