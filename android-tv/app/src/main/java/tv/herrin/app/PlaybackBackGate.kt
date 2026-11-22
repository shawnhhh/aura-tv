package tv.herrin.app

/** Compose, PlayerView and the system dispatcher share one physical Back transition. */
internal class PlaybackBackGate {
    private var key: Long? = null
    private var at: Long? = null
    fun accept(downTime: Long?, now: Long): Boolean {
        if (downTime != null && downTime == key) return false
        // Dispatcher callbacks carry no KeyEvent. Coalesce both arrival orders;
        // two distinct physical presses still remain independent even when fast.
        if (at?.let { now - it in 0..100 } == true && (downTime == null || key == null)) {
            if (downTime != null) key = downTime
            return false
        }
        key = downTime
        at = now
        return true
    }
}
