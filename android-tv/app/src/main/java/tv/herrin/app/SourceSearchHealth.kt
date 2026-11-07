package tv.herrin.app

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

internal enum class SourceSearchStage { DISCOVER, ACTIVATE, SEARCH, DETAILS, PARSE, MEDIA }
internal enum class SourceSearchOutcome { CHECKING, VERIFIED, NO_MATCH, NO_EPISODES, TIMEOUT, ACTIVATE_FAILED,
    PARSE_FAILED, MEDIA_FAILED, AUTH_REQUIRED, RATE_LIMITED, EXPIRED, NETWORK, ERROR }
internal data class SourceSearchEvent(val source: String, val stage: SourceSearchStage,
    val outcome: SourceSearchOutcome, val elapsedMs: Long = 0, val completed: Int = 0, val total: Int = 0,
    val retryAfterMs: Long? = null, val nodeComplete: Boolean = false)
internal class SourceCandidateFailure(val stage:SourceSearchStage,val outcome:SourceSearchOutcome):Exception()
internal object SourceSearchPolicy {
    const val FOREGROUND_MS=8_000L
    const val EXTENDED_MS=15_000L
    const val NODE_MS=3_500L
    const val STAGE_MS=1_200L
}

/** Stores hashes, finite codes and numbers only. Never persists offers, headers or media URLs. */
internal class SourceHealthIndex(private val file: File?, private val scope: String,
    private val clock: () -> Long = System::currentTimeMillis) {
    internal data class Entry(val searchAt: Long = 0, val mediaAt: Long = 0, val failedAt: Long = 0,
        val stage: SourceSearchStage = SourceSearchStage.SEARCH, val outcome: SourceSearchOutcome = SourceSearchOutcome.NO_MATCH,
        val latencyMs: Long = 0, val failures: Int = 0, val retryAt: Long = 0)
    private val entries = linkedMapOf<String, Entry>()
    init {
        try {
            if (file?.isFile == true && file.length() in 1..64_000) {
                val json = JSONObject(file.readText())
                if (json.optString("scope") == digest(scope)) {
                    val values = json.optJSONObject("entries") ?: JSONObject()
                    values.keys().asSequence().take(128).filter { it.matches(Regex("[a-f0-9]{64}")) }.forEach { id ->
                        val e=values.getJSONObject(id)
                        val entry=Entry(e.optLong("search"),e.optLong("media"),e.optLong("failed"),
                            SourceSearchStage.valueOf(e.getString("stage")),SourceSearchOutcome.valueOf(e.getString("outcome")),
                            e.optLong("latency").coerceIn(0,150_000),e.optInt("failures").coerceIn(0,20),e.optLong("retry"))
                        if (clock()-maxOf(entry.searchAt,entry.mediaAt,entry.failedAt) in 0..7*DAY ||entry.retryAt>clock()) entries[id]=entry
                    }
                }
            }
        } catch (_: Exception) { entries.clear() }
    }
    @Synchronized fun record(id: String, stage: SourceSearchStage, result: SourceSearchOutcome,
        elapsedMs: Long, retryAfterMs: Long? = null) {
        if (result == SourceSearchOutcome.CHECKING) return
        val key=digest(id); val old=entries[key] ?: Entry(); val now=clock()
        val success=result==SourceSearchOutcome.VERIFIED
        val failures=if(success &&stage==SourceSearchStage.MEDIA)0 else if(success)old.failures else (old.failures+1).coerceAtMost(20)
        val cooldown=when(result){
            SourceSearchOutcome.RATE_LIMITED -> (retryAfterMs ?: 60_000).coerceAtLeast(1_000)
            SourceSearchOutcome.AUTH_REQUIRED -> 5*60_000L
            SourceSearchOutcome.TIMEOUT -> (2_000L shl failures.coerceAtMost(5)).coerceAtMost(120_000)
            SourceSearchOutcome.NETWORK -> 10_000L
            SourceSearchOutcome.EXPIRED -> 30_000L
            SourceSearchOutcome.ACTIVATE_FAILED -> 60_000L
            else -> 0L
        }
        entries[key]=Entry(if(success && stage==SourceSearchStage.SEARCH)now else old.searchAt,
            if(success && stage==SourceSearchStage.MEDIA)now else old.mediaAt,if(success)old.failedAt else now,
            stage,result,if(old.latencyMs==0L)elapsedMs else (old.latencyMs*3+elapsedMs)/4,failures,
            if(success &&stage==SourceSearchStage.MEDIA)now else maxOf(old.retryAt,
                if(cooldown>Long.MAX_VALUE-now)Long.MAX_VALUE else now+cooldown))
        while(entries.size>128)entries.remove(entries.keys.first())
    }
    @Synchronized fun eligible(id: String): Boolean = (entries[digest(id)]?.retryAt ?: 0) <= clock()
    @Synchronized fun rank(id: String): Long {
        val e=entries[digest(id)] ?: return 0
        if(e.retryAt>clock())return Long.MIN_VALUE/2
        return (if(e.mediaAt>0 &&clock()-e.mediaAt in 0..6*HOUR) 1_000_000 else 0) +
            (if(e.searchAt>0 &&clock()-e.searchAt in 0..HOUR)100_000 else 0) - e.latencyMs - e.failures*1000
    }
    @Synchronized fun save(isCurrent:()->Boolean={true}) { synchronized(ioLock) {
        val dest=file ?: return
        if(!isCurrent())return
        val values=JSONObject()
        entries.forEach{(id,e)->values.put(id,JSONObject().put("search",e.searchAt).put("media",e.mediaAt)
            .put("failed",e.failedAt).put("stage",e.stage.name).put("outcome",e.outcome.name)
            .put("latency",e.latencyMs).put("failures",e.failures).put("retry",e.retryAt))}
        val temp=File(dest.parentFile,dest.name+".tmp")
        try { dest.parentFile?.mkdirs();temp.writeText(JSONObject().put("scope",digest(scope)).put("entries",values).toString())
            if(!isCurrent() ||!temp.renameTo(dest))temp.delete() } catch (_: java.io.IOException){temp.delete()}
    } }
    companion object {
        private val ioLock=Any()
        private const val HOUR=3_600_000L
        private const val DAY=24*HOUR
        fun digest(value: String): String {
            val bytes=MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            val hex="0123456789abcdef";val chars=CharArray(bytes.size*2)
            bytes.forEachIndexed{i,b->val n=b.toInt() and 255;chars[i*2]=hex[n ushr 4];chars[i*2+1]=hex[n and 15]}
            return String(chars)
        }
        fun httpOutcome(code: Int)=when(code){401,403->SourceSearchOutcome.AUTH_REQUIRED;429->SourceSearchOutcome.RATE_LIMITED
            404,410->SourceSearchOutcome.EXPIRED;else->SourceSearchOutcome.NETWORK}
        fun retryAfter(value: String?, now: Long): Long? = value?.trim()?.toLongOrNull()?.takeIf{it>=0}?.let{it.coerceAtMost(Long.MAX_VALUE/1000)*1000}
            ?: runCatching{java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli().minus(now).coerceAtLeast(1000)}.getOrNull()
    }
}

/** Inherited across dispatcher changes; never changes authorization or FongMi decode behavior. */
internal class SpiderRequestBudget(val milliseconds: Long) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<SpiderRequestBudget>
}
internal class SpiderSearchAuthority(val configuration:Long):kotlin.coroutines.AbstractCoroutineContextElement(Key){
    companion object Key:kotlin.coroutines.CoroutineContext.Key<SpiderSearchAuthority>
}
