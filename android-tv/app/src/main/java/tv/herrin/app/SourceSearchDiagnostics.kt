package tv.herrin.app

/** Fixed finite stages and opaque hashes only; no request/response bodies or media credentials. */
internal object SourceSearchDiagnostics {
    private const val TAG="HerrinSource"
    private val events=ArrayDeque<String>()
    private var started=0L
    private var candidateAt=-1L
    private var mediaAt=-1L
    private var completed=0
    private var timeouts=0
    @Synchronized fun begin(){events.clear();started=System.nanoTime();candidateAt=-1;mediaAt=-1;completed=0;timeouts=0}
    @Synchronized fun record(event:SourceSearchEvent){
        val at=(System.nanoTime()-started)/1_000_000
        if(event.stage==SourceSearchStage.SEARCH &&event.outcome==SourceSearchOutcome.VERIFIED &&candidateAt<0)candidateAt=at
        if(event.stage==SourceSearchStage.MEDIA &&event.outcome==SourceSearchOutcome.VERIFIED &&mediaAt<0)mediaAt=at
        if(event.outcome==SourceSearchOutcome.TIMEOUT)timeouts++
        completed=maxOf(completed,event.completed)
        val line="source=${SourceHealthIndex.digest(event.source).take(12)} stage=${event.stage} result=${event.outcome} duration_ms=${event.elapsedMs} at_ms=$at completed=${event.completed} total=${event.total}"
        if(events.size==96)events.removeFirst();events.addLast(line)
        if(android.util.Log.isLoggable(TAG,android.util.Log.DEBUG)) {
            android.util.Log.d(TAG,line)
        }
    }
    @Synchronized fun summary()="SOURCE_SEARCH first_candidate_ms=$candidateAt first_media_ms=$mediaAt completed=$completed timeout_events=$timeouts\n"+events.takeLast(16).joinToString("\n")
}
