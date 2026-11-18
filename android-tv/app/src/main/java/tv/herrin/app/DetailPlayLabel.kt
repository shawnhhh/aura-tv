package tv.herrin.app

/** Only authoritative coordinates and watched position belong in the action label. */
internal object DetailPlayLabel {
    fun text(season: Int? = null, episode: Int? = null, positionMs: Long? = null): String = buildList {
        add("播放")
        season?.takeIf { it > 0 }?.let { add("第${it}季") }
        episode?.takeIf { it > 0 }?.let { add("第${it}集") }
        positionMs?.takeIf { it > 0 }?.let {
            val seconds = it / 1000
            add(if (seconds >= 3600) "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
                else "%02d:%02d".format(seconds / 60, seconds % 60))
        }
    }.joinToString(" ")
}
