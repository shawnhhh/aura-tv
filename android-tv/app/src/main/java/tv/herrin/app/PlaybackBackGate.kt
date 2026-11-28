package tv.herrin.app

/** Compose, PlayerView and the system dispatcher share one physical Back transition. */
internal class PlaybackBackGate {
    private var key: Long? = null
    private var at: Long? = null
    private var firstPressAt: Long? = null
    private var exited = false

    fun press(downTime: Long?, now: Long, closeTopLayer: () -> Unit, exit: () -> Unit) {
        if (exited || !accept(downTime, now)) return
        if (firstPressAt?.let { now - it in 0..500 } == true) {
            firstPressAt = null
            exited = true
            exit()
        } else {
            firstPressAt = now
            closeTopLayer()
        }
    }

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
