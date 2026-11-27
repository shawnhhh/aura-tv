package tv.herrin.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/** Serial metadata requests with a persistent per-file retry time. */
internal class PersonalMetadataScraper(
    private val index: PersonalMediaIndex,
    private val discovery: HerrinTmdbDiscovery,
    private val token: String,
    private val currentToken: () -> String,
    private val publish: () -> Unit
) {
    private val searches = LinkedHashMap<Pair<String, String>, List<Film>>()
    private val details = LinkedHashMap<Pair<String, Int>, Film>()
    private val ticker = System::currentTimeMillis
    private var stopped = false

    suspend fun page(folder: PersonalMediaIndex.Folder): Boolean {
        if (stopped) return false
        val rows = index.dueMetadata(folder.id, index.scrapeCursor(folder.id), ticker(), manualOnly = folder.type == "video")
        if (rows.isEmpty()) {
            index.scrapeCursor(folder.id, "")
            return false
        }
        for (row in rows) {
            coroutineContext.ensureActive()
            StartupBackgroundWork.awaitReady()
            if (currentToken() != token) return false
            val code = try {
                scrape(row, folder)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: ArtworkAuthenticationFailure) {
                log("SCRAPE_AUTH_FAILED")
                stopped = true
                return false
            } catch (limited: ArtworkRateLimitFailure) {
                log("SCRAPE_RATE_LIMITED")
                stopped = true
                return false
            } catch (http: ArtworkHttpStatusFailure) {
                log("SCRAPE_HTTP_${http.status}")
                "SCRAPE_HTTP_FAILURE"
            } catch (_: java.io.IOException) {
                "SCRAPE_NETWORK_FAILURE"
            } catch (_: Exception) {
                "SCRAPE_RESPONSE_INVALID"
            }
            coroutineContext.ensureActive()
            if (currentToken() != token) return false
            if (code != null) {
                val retryAt = ticker() + if (code in setOf("SCRAPE_AMBIGUOUS", "SCRAPE_NO_MATCH")) DAY_MS else RETRY_MS
                val pendingCode = row.optString("scrape_code")
                    .takeIf { it in setOf("MANUAL_MATCH", "DETAILS_PENDING") } ?: code
                index.delayMetadata(row, pendingCode, retryAt)
                log(code)
            }
            index.scrapeCursor(folder.id, row.getString("id"))
        }
        if (rows.size < 32) index.scrapeCursor(folder.id, "")
        return rows.size == 32
    }

    private suspend fun scrape(row: JSONObject, folder: PersonalMediaIndex.Folder): String? {
        val existingId = row.optInt("tmdb")
        val manual = row.optInt("manual") == 1
        val hint = runCatching { JSONObject(row.optString("metadata")) }.getOrDefault(JSONObject())
        val existingType = hint.optString("identityType").ifBlank { row.optString("type") }
        val candidate: Film?
        val identity = when {
            existingId > 0 && existingType in setOf("movie", "tv") -> {
                candidate = null
                existingType to existingId
            }
            hint.optInt("nfoTmdb") > 0 && hint.optString("nfoType") in setOf("movie", "tv") -> {
                candidate = null
                hint.getString("nfoType") to hint.getInt("nfoTmdb")
            }
            else -> {
                val parsed = PersonalStoragePolicy.parse(row.getString("name"), row.getString("path").substringBeforeLast('/', ""))
                val names = (listOf(parsed.title) + parsed.aliases).map(PersonalMediaMatching::canonical)
                    .filter(String::isNotBlank).distinct().take(3)
                val year = row.optInt("year").takeIf { it > 0 }
                val type = when (row.optString("type")) {
                    "anime", "tv" -> "tv"
                    "movie" -> "movie".takeIf { folder.type != "auto" }
                    else -> null
                }
                val result = match(names, year, type)
                if (result.ambiguous) return "SCRAPE_AMBIGUOUS"
                candidate = result.film ?: return "SCRAPE_NO_MATCH"
                discovery.identity(candidate) ?: return "SCRAPE_INVALID_ID"
            }
        }
        coroutineContext.ensureActive()
        if (currentToken() != token) return null
        // Publish verified search identity and available artwork immediately.
        // A separate queue entry retries failed detail retrieval without another search.
        val pending = candidate != null && index.acceptMatch(row, candidate, identity.second, identity.first,
            detailsPending = true, retryAt = ticker() + RETRY_MS)
        if (pending) publish()
        if (candidate != null && !pending) return null
        val detail = official(identity) ?: return "SCRAPE_DETAILS_PENDING"
        coroutineContext.ensureActive()
        if (currentToken() != token) return null
        if (manual) {
            if (index.enrichCorrection(row.getString("id"), identity.second, identity.first, detail)) publish()
        } else {
            val claim = if (pending) JSONObject(row.toString()).put("tmdb", identity.second) else row
            val needsArtwork = detail.artworkUrl == null || detail.synopsis.isBlank()
            if (index.acceptMatch(claim, detail, identity.second, identity.first,
                    detailsPending = needsArtwork, retryAt = ticker() + DAY_MS)) publish()
        }
        return null
    }

    private suspend fun match(names: List<String>, year: Int?, type: String?): PersonalMediaMatching.Decision {
        val primary = names.firstOrNull() ?: return PersonalMediaMatching.Decision(null)
        val collected = mutableListOf<Film>()
        collected += search(primary, "zh-CN")
        val initial = PersonalMediaMatching.decide(names, year, type, collected, discovery, allowApproximate = false)
        if (initial.film != null || initial.ambiguous) return initial
        // Original and alternative localized titles, with a strict per-work request cap.
        val secondary = names.drop(1).firstOrNull() ?: primary
        collected += search(secondary, "en-US")
        collected += search(primary, "zh-TW")
        return PersonalMediaMatching.decide(names, year, type, collected, discovery)
    }

    private suspend fun search(query: String, language: String): List<Film> {
        val key = query to language
        searches[key]?.let { return it }
        val result = paced { discovery.search(query, token, searchLanguage = language).films }
        if (searches.size >= 128) searches.remove(searches.keys.first())
        searches[key] = result
        return result
    }

    private suspend fun official(identity: Pair<String, Int>): Film? {
        details[identity]?.let { return it }
        val result = paced { discovery.identified(identity.first, identity.second, token) } ?: return null
        if (details.size >= 128) details.remove(details.keys.first())
        details[identity] = result
        return result
    }

    private suspend fun <T> paced(operation: suspend () -> T): T {
        delay(320)
        coroutineContext.ensureActive()
        if (currentToken() != token) throw CancellationException("Metadata credential changed")
        return operation()
    }

    private fun log(code: String) = HerrinPlaybackDiagnosticJournal.record("PERSONAL", code)

    companion object {
        private const val RETRY_MS = 30 * 60_000L
        private const val DAY_MS = 24 * 60 * 60_000L
    }
}
