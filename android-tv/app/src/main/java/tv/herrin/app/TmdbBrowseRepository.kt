package tv.herrin.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

internal data class TmdbBrowseBatch(
    val entries: List<TmdbBrowseEntry>, val page: Int, val hasMore: Boolean, val unknown: Int = 0
)

/** Public TMDB metadata only. No FilmSource, Spider, playback lookup or provider credentials. */
internal class TmdbBrowseRepository(
    private val fetcher: ArtworkJsonFetcher = NetworkArtworkFetcher,
    private val directory: File? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val network = Semaphore(3)
    private val proofLocks = Array(64) { Mutex() }
    private val collections = ConcurrentHashMap<String, Map<Int, Int>>()
    private val ambiguous = ConcurrentHashMap<String, Set<Int>>()
    private val parser = HerrinTmdbDiscovery()
    private fun prefix(token: String) = MessageDigest.getInstance("SHA-256")
        .digest(("browse-v2|zh-CN|$token").toByteArray()).joinToString("") { "%02x".format(it) }
    private fun today() = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate()
    private fun file(key: String) = directory?.let { File(it, "$key.json") }
    private fun read(key: String): JSONObject? = file(key)?.takeIf { it.isFile && it.length() in 1..1_000_000 }
        ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
    private fun write(key: String, json: JSONObject) {
        val target = file(key) ?: return
        val raw = json.toString()
        if (raw.length > 1_000_000) return
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + "." + java.util.UUID.randomUUID() + ".tmp")
        try {
            temporary.writeText(raw)
            if (!temporary.renameTo(target)) return
            val group = if ("-page-" in key) "-page-" else if ("-proof-" in key) "-proof-" else "-relations"
            directory?.listFiles()?.filter { it.extension == "json" && group in it.name }
                ?.sortedByDescending(File::lastModified)?.drop(if (group == "-proof-") 512 else 64)?.forEach(File::delete)
        } finally { temporary.delete() }
    }
    private suspend fun get(path: String, token: String): JSONObject = network.withPermit {
        if (token.isBlank()) throw ArtworkAuthenticationFailure(401)
        runInterruptible(Dispatchers.IO) {
            val endpoint = TmdbEndpoints.PRIMARY + path + (if ('?' in path) "&" else "?") + "language=zh-CN"
            val raw = fetcher.load(endpoint, token) ?: throw IOException("TMDB_EMPTY_RESPONSE")
            if (raw.length > 1_000_000) throw IOException("TMDB_RESPONSE_LIMIT")
            val json = JSONObject(raw)
            if (json.optBoolean("success", true).not()) throw IOException("TMDB_REJECTED")
            json
        }
    }
    private val rowFields = setOf("id", "title", "name", "original_title", "original_name", "release_date",
        "first_air_date", "overview", "poster_path", "backdrop_path", "vote_average", "vote_count",
        "genre_ids", "adult", "popularity", "media_type")
    private fun safeRow(row: JSONObject) = JSONObject().apply {
        rowFields.forEach { if (row.has(it)) put(it, row.get(it)) }
    }
    private fun safeProof(raw: JSONObject): JSONObject = JSONObject().apply {
        listOf("id", "status", "release_date", "first_air_date", "last_air_date", "number_of_episodes")
            .forEach { if (raw.has(it)) put(it, raw.get(it)) }
        raw.optJSONArray("origin_country")?.let { countries ->
            put("origin_country", JSONArray((0 until minOf(8, countries.length())).map { countries.optString(it).take(2) }))
        }
        raw.optJSONArray("production_countries")?.let { countries ->
            put("production_countries", JSONArray().apply { for (i in 0 until minOf(20, countries.length())) {
                val code = countries.optJSONObject(i)?.optString("iso_3166_1").orEmpty()
                if (code.matches(Regex("[A-Z]{2}"))) put(JSONObject().put("iso_3166_1", code))
            } })
        }
        raw.optJSONArray("genres")?.let { genres ->
            put("genres", JSONArray().apply { for (i in 0 until minOf(12, genres.length()))
                put(JSONObject().put("id", genres.optJSONObject(i)?.optInt("id", 0))) })
        }
        raw.optJSONObject("belongs_to_collection")?.optInt("id")?.takeIf { it > 0 }?.let {
            put("belongs_to_collection", JSONObject().put("id", it))
        }
        raw.optJSONObject("last_episode_to_air")?.let {
            put("last_episode_to_air", JSONObject().put("air_date", it.optString("air_date").take(10)))
        }
        raw.optJSONObject("release_dates")?.optJSONArray("results")?.let { countries ->
            val earliest = mutableMapOf<Int, String>()
            for (i in 0 until minOf(100, countries.length())) {
                val rows = countries.optJSONObject(i)?.optJSONArray("release_dates") ?: continue
                for (j in 0 until minOf(100, rows.length())) rows.optJSONObject(j)?.let {
                    val type = it.optInt("type")
                    val day = it.optString("release_date").take(10)
                    if (type in 1..6 && runCatching { java.time.LocalDate.parse(day) }.isSuccess &&
                        (earliest[type] == null || day < earliest.getValue(type))) earliest[type] = day
                }
            }
            put("release_dates", JSONObject().put("results", JSONArray().put(JSONObject().put("release_dates",
                JSONArray().apply { earliest.forEach { (type, date) -> put(JSONObject().put("type", type).put("release_date", date)) } }))))
        }
    }

    private fun collectionMap(token: String): Map<Int, Int> = collections.getOrPut(prefix(token)) {
        val saved = read(prefix(token) + "-relations") ?: JSONObject()
        val conflicts = saved.optJSONArray("ambiguous")?.let { array ->
            (0 until minOf(100, array.length())).map { array.optInt(it) }.toSet()
        }.orEmpty()
        ambiguous[prefix(token)] = conflicts
        val rows = saved.optJSONObject("collections") ?: JSONObject()
        rows.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to rows.optInt(k) } }
            .filter { it.first > 0 && it.first !in conflicts && it.second in TmdbAnimeIp.serialMovies.values }.take(100).toMap()
    }

    private fun learn(type: String, id: Int, proof: JSONObject, token: String) {
        if (type != "movie" || id !in TmdbAnimeIp.serialMovies) return
        val collection = proof.optJSONObject("belongs_to_collection")?.optInt("id", 0) ?: 0
        if (collection <= 0) return
        synchronized(collections) {
            val current = collectionMap(token)
            val expected = TmdbAnimeIp.serialMovies.getValue(id)
            val conflicts = (ambiguous[prefix(token)].orEmpty() +
                if (current[collection] != null && current[collection] != expected) setOf(collection) else emptySet()).take(100).toSet()
            ambiguous[prefix(token)] = conflicts
            val learned = (if (collection in conflicts) current - collection else current + (collection to expected))
                .toList().takeLast(100).toMap()
            collections[prefix(token)] = learned
            write(prefix(token) + "-relations", JSONObject().put("collections", JSONObject(learned.mapKeys { it.key.toString() }))
                .put("ambiguous", JSONArray(conflicts.toList())))
        }
    }
    /** Learn series identity from reviewed serial-film IDs in the background, never from title words. */
    suspend fun warmAnimeRelations(token: String) = withContext(Dispatchers.IO) { coroutineScope {
        TmdbAnimeIp.serialMovies.keys.map { id -> async {
            try { proof("movie", id, token) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Unknown stays a movie; next refresh may retry. */ }
        } }.awaitAll()
    }
    }
    private suspend fun proof(type: String, id: Int, token: String, force: Boolean = false): JSONObject {
        val key = prefix(token) + "-proof-$type-$id"
        return proofLocks[(key.hashCode() and Int.MAX_VALUE) % proofLocks.size].withLock {
            val disk = read(key)
            val recent = disk?.takeIf { !force && clock() - it.optLong("saved") in 0..86_400_000L }?.optJSONObject("proof")
            if (recent != null) { learn(type, id, recent, token); return@withLock recent }
            val fresh = get("/$type/$id" + if (type == "movie") "?append_to_response=release_dates" else "", token)
            if (fresh.optInt("id") != id) throw IOException("TMDB_ID_MISMATCH")
            safeProof(fresh).also {
                write(key, JSONObject().put("saved", clock()).put("proof", it))
                learn(type, id, it, token)
            }
        }
    }
    private fun entry(row: JSONObject, type: String, proof: JSONObject, token: String): TmdbBrowseEntry? {
        if (type !in setOf("movie", "tv")) return null
        val film = parser.parsePage(JSONObject().put("results", JSONArray().put(row)).toString(), type).firstOrNull() ?: return null
        val genres = row.optJSONArray("genre_ids")?.let { a -> (0 until minOf(12, a.length())).map { a.optInt(it) } }?.toSet()
            .orEmpty().ifEmpty { proof.optJSONArray("genres")?.let { a ->
                (0 until minOf(12, a.length())).map { a.optJSONObject(it)?.optInt("id") ?: 0 }.toSet() }.orEmpty() }
        val id = row.optInt("id")
        val category = TmdbAnimeIp.category(type, id, genres,
            proof.optJSONObject("belongs_to_collection")?.optInt("id"), collectionMap(token))
        val countries = buildSet {
            proof.optJSONArray("origin_country")?.let { a -> for (i in 0 until minOf(8, a.length())) add(a.optString(i)) }
            proof.optJSONArray("production_countries")?.let { a -> for (i in 0 until minOf(20, a.length()))
                a.optJSONObject(i)?.optString("iso_3166_1")?.let(::add) }
        }.filter { it.matches(Regex("[A-Z]{2}")) }.toSet()
        return TmdbBrowseEntry(film.copy(category = category, regions = countries.toList(),
            genres = TmdbGenreClassification.labels(genres.toList())), type, countries,
            TmdbReleaseEvidence.evaluate(type, proof, today()), row.optInt("vote_count"),
            row.optDouble("popularity", 0.0).takeIf { it.isFinite() } ?: 0.0, safeRow(row), proof)
    }
    /** Cache-only classification for TMDB search; caller parses metadata off the UI thread. */
    fun cachedClassification(type: String, id: Int, genres: Set<Int>, token: String): String {
        val detail = read(prefix(token) + "-proof-$type-$id")?.optJSONObject("proof")
        return TmdbAnimeIp.category(type, id, genres, detail?.optJSONObject("belongs_to_collection")?.optInt("id"),
            collectionMap(token))
    }
    suspend fun cached(spec: TmdbRailSpec, page: Int, token: String): TmdbBrowseBatch? = withContext(Dispatchers.IO) {
        val json = read(prefix(token) + "-page-" + spec.key + "-$page") ?: return@withContext null
        decodeBatch(spec, page, token, json)
    }
    private fun decodeBatch(spec: TmdbRailSpec, page: Int, token: String, json: JSONObject): TmdbBrowseBatch {
        val rows = json.optJSONArray("entries") ?: JSONArray()
        val items = (0 until minOf(100, rows.length())).mapNotNull { i ->
            val r = rows.optJSONObject(i) ?: return@mapNotNull null
            entry(r.getJSONObject("row"), r.getString("type"), r.getJSONObject("proof"), token)?.takeIf { eligible(spec, it) }
        }
        return TmdbBrowseBatch(select(spec, items), page, json.optBoolean("more"), json.optInt("unknown"))
    }
    private fun select(spec: TmdbRailSpec, entries: List<TmdbBrowseEntry>): List<TmdbBrowseEntry> = when (spec.kind) {
        TmdbRailKind.DOMESTIC -> TmdbDomesticAllocator.allocate(entries, 12)
        TmdbRailKind.RATED -> entries.sortedByDescending { it.film.ratingValue ?: 0.0 }.distinctBy { it.film.id }.take(20)
        TmdbRailKind.LATEST -> entries.sortedByDescending { it.availability.date }.distinctBy { it.film.id }.take(20)
        else -> entries.distinctBy { it.film.id }.take(20)
    }

    fun eligible(spec: TmdbRailSpec, entry: TmdbBrowseEntry): Boolean =
        entry.availability.state == TmdbAvailability.AVAILABLE &&
            (spec.page == TmdbBrowsePage.HOME || entry.film.category == spec.page.label) &&
            (spec.kind != TmdbRailKind.RATED || entry.votes >= spec.minVotes(entry.type)) &&
            (spec.kind != TmdbRailKind.DOMESTIC || entry.countries.any { it in setOf("CN", "HK", "TW") })

    suspend fun load(spec: TmdbRailSpec, page: Int, token: String, force: Boolean = false): TmdbBrowseBatch = withContext(Dispatchers.IO) {
        require(page in 1..50)
        val key = prefix(token) + "-page-" + spec.key + "-$page"
        val old = read(key)
        if (!force && old != null && clock() - old.optLong("saved") in 0..21_600_000L)
            return@withContext decodeBatch(spec, page, token, old)
        var more = false
        var unknown = 0
        var failures = 0
        val observed = mutableListOf<TmdbBrowseEntry>()
        val seen = hashSetOf<String>()
        suspend fun rows(path: String, fallbackType: String): List<Pair<JSONObject, String>> {
            val root = get(path + (if ('?' in path) "&" else "?") + "page=$page", token)
            if (root.optInt("page") != page || !root.has("results")) throw IOException("TMDB_PAGE_MISMATCH")
            more = more || root.optInt("total_pages", 1) > page
            val array = root.getJSONArray("results")
            return (0 until minOf(20, array.length())).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                val type = row.optString("media_type").takeIf { it in setOf("movie", "tv") } ?: fallbackType
                if (type !in setOf("movie", "tv") || row.optBoolean("adult") || row.optInt("id") <= 0) null
                else safeRow(row) to type
            }
        }
        suspend fun enrich(candidates: List<Pair<JSONObject, String>>, limit: Int): List<TmdbBrowseEntry> = coroutineScope {
            candidates.asSequence().filter { seen.add(it.second + "-" + it.first.optInt("id")) }.take(limit).toList().map { (row, type) -> async {
                try { entry(row, type, proof(type, row.getInt("id"), token, force), token) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            } }.awaitAll().also { results ->
                failures += results.count { it == null }
                unknown += results.count { e -> e == null || e.availability.state == TmdbAvailability.UNKNOWN }
                observed += results.filterNotNull()
            }
                .filterNotNull().filter { eligible(spec, it) }
        }
        val result = if (spec.kind == TmdbRailKind.DOMESTIC) {
            val all = mutableListOf<TmdbBrowseEntry>()
            for (country in listOf("CN", "HK", "TW")) {
                val candidates = spec.types.flatMap { type -> rows(spec.endpoints(type, today(), country).first(), type) }
                    .sortedByDescending { it.first.optDouble("popularity", 0.0) }
                all += enrich(candidates, if (country == "CN") 20 else 8)
            }
            TmdbDomesticAllocator.allocate(all, 12)
        } else {
            val candidates = if (spec.kind == TmdbRailKind.HOT && spec.page == TmdbBrowsePage.HOME)
                rows("/trending/all/week", "") else spec.types.map { type -> rows(spec.endpoints(type, today()).first(), type) }
                    .flatMap { list -> list.mapIndexed { index, pair -> index to pair } }.sortedBy { it.first }.map { it.second }
            var eligible = enrich(candidates, 24)
            if (spec.kind == TmdbRailKind.HOT && eligible.size < 12) {
                val supplements = spec.types.flatMap { type -> rows(spec.endpoints(type, today()).last(), type) }
                eligible = eligible + enrich(supplements, 20)
            }
            when (spec.kind) {
                TmdbRailKind.RATED -> eligible.sortedByDescending { it.film.ratingValue ?: 0.0 }
                TmdbRailKind.LATEST -> eligible.sortedByDescending { it.availability.date }
                else -> eligible
            }.distinctBy { it.film.id }.take(20)
        }
        if (observed.isEmpty() && failures > 0) throw IOException("TMDB_PROOF_UNAVAILABLE")
        val batch = TmdbBrowseBatch(result, page, more && page < 50, unknown)
        write(key, JSONObject().put("saved", clock()).put("more", batch.hasMore).put("unknown", unknown)
            .put("entries", JSONArray().apply { observed.forEach {
                put(JSONObject().put("type", it.type).put("row", it.raw).put("proof", it.proof))
            } }))
        batch
    }
}
