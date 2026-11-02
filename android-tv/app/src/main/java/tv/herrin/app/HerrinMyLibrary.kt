package tv.herrin.app

/**
 * A personal rail over the currently authorized catalog. Never persists
 * credentials or exposes movies that are not present in the active catalog.
 * Unfinished watches come first, followed by favorites in their saved order.
 */
internal object HerrinMyLibrary {
    fun combine(recent: List<Film>, favorites: List<Film>): List<Film> =
        (recent + favorites).distinctBy(Film::id)
}
