package tv.herrin.app

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.CancellationException

/** Random, process-independent correlation only: never an authorization capability or a media ID. */
internal object PlaybackDiagnosticSession {
    private val current = ThreadLocal<String?>()
    fun id(): String? = current.get()
    fun newId(): String = java.util.UUID.randomUUID().toString()
    fun context(id: String) = current.asContextElement(id.takeIf { it.matches(Regex("[a-f0-9-]{36}")) })
    suspend fun <T> stage(stage: String, event: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        HerrinPlaybackDiagnosticJournal.record(stage, event + "_STARTED")
        var outcome = "FINISHED"
        try { return block() }
        catch (cancel: CancellationException) { outcome = "CANCELLED"; throw cancel }
        catch (error: Throwable) { outcome = "FAILED"; throw error }
        finally {
            HerrinPlaybackDiagnosticJournal.record(stage, event + "_" + outcome,
                "MS=" + ((System.nanoTime()-start)/1_000_000).coerceAtMost(99999))
        }
    }
}
