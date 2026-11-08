package tv.herrin.app

/** Bounded process-local protocol evidence; never accepts responses, headers or URLs. */
internal object DiscoveryProtocolTrace {
    private val events = ArrayDeque<String>()
    @Synchronized fun record(site: String, kind: String, raw: Int, parsed: Int, query: String = "") {
        val keyword = query.takeIf { "://" !in it && '=' !in it && '&' !in it }
            .orEmpty().replace(Regex("[^\\p{L}\\p{N} .·_-]"), "").take(120)
        val line = "site=${SourceHealthIndex.digest(site).take(12)} protocol=$kind keyword=$keyword " +
            "raw=$raw parsed=$parsed discarded=${(raw-parsed).coerceAtLeast(0)} reason=INVALID_OR_LIMITED_ONLY"
        if(events.size == 96) events.removeFirst()
        events.addLast(line)
    }
    @Synchronized fun summary() = events.joinToString("\n")
}
