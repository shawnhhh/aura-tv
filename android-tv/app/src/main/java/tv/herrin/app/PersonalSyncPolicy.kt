package tv.herrin.app

internal object PersonalSyncPolicy {
    const val INTERVAL_MS = 30 * 60_000L
    fun due(scanned: Long, now: Long): Boolean = scanned <= 0 || now < scanned || now - scanned >= INTERVAL_MS
}
