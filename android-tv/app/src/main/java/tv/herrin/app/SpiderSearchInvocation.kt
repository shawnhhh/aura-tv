package tv.herrin.app

/** FongMi SiteViewModel permits quick mode only for sites declaring quickSearch=1.
 * A blank unsupported entry point is distinct from a valid empty list or an
 * authorization error; only that blank case may try the declared alternate mode.
 */
internal object SpiderSearchInvocation {
    data class Attempt(val quick: Boolean, val body: String)
    data class Reply(val body: String, val quick: Boolean, val attempts: List<Attempt>)
    fun execute(quickAllowed: Boolean, search: (Boolean) -> String): Reply {
        val normal = search(false)
        val attempts = mutableListOf(Attempt(false,normal))
        if (normal.isBlank() && quickAllowed) {
            val quick = search(true)
            attempts += Attempt(true,quick)
            return Reply(quick,true,attempts)
        }
        return Reply(normal,false,attempts)
    }
}
