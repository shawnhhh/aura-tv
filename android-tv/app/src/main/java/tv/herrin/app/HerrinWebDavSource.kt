package tv.herrin.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Explicitly configured user-owned NAS or cloud WebDAV endpoint. The API itself
 * cannot discover arbitrary Aliyun/Quark/115 shares; these require the user's
 * authorized WebDAV-compatible gateway or their own supported connector.
 */
internal data class HerrinWebDavAccount(
    val name: String, val rootUrl: String,
    val username: String = "", val password: String = "",
    val category: HerrinSourceType = HerrinSourceType.NAS,
    val allowInsecureHttp: Boolean = false
)

internal class HerrinWebDavSource(
    private val account: HerrinWebDavAccount,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS).build()
) : HerrinFilmSource {
    init {
        require(account.category in listOf(HerrinSourceType.NAS, HerrinSourceType.CLOUD))
        val root = URI(account.rootUrl)
        require(root.host != null && root.rawUserInfo == null && root.fragment == null &&
            (root.scheme.equals("https", true) ||
                (account.allowInsecureHttp && root.scheme.equals("http", true))))
        require(account.name.isNotBlank() && account.rootUrl.length <= 2048)
    }
    override val id: String = "webdav:" + SourceHealthIndex.digest(
        account.category.name+"\u0000"+account.rootUrl+"\u0000"+account.username+"\u0000"+account.name)
    override val type: HerrinSourceType = account.category
    // Normalize a user-pasted DAV collection without a trailing slash.
    // Relative XML hrefs resolve as children only when the base ends in '/'.
    // Preserve raw query data rather than re-encoding any gateway signature.
    private val root = URI(account.rootUrl.substringBefore('?').trimEnd('/') + "/" +
        (if ('?' in account.rootUrl) "?" + account.rootUrl.substringAfter('?') else ""))
    private val rootPrefix = root.path.orEmpty().trimEnd('/') + "/"
    private val extensions = setOf("mp4", "mkv", "mov", "m4v", "ts", "m2ts", "webm", "avi")
    private val seasonEp = Regex("""(?i)(?:^|[\s._-])S(\d{1,2})E(\d{1,3})(?=$|[\s._-])""")

    private data class DavEntry(val address: String, val isCollection: Boolean)

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        runInterruptible {
            val xml = "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
            val request = Request.Builder().url(root.toString()).header("Depth", "0")
                .method("PROPFIND", xml.toRequestBody("application/xml".toMediaType()))
                .apply { if (account.username.isNotBlank()) header("Authorization", Credentials.basic(account.username, account.password)) }
                .build()
            client.newCall(request).execute().use { it.code == 207 }
        }
    }

    override suspend fun search(identity: HerrinFilmIdentity): List<HerrinSourceMovie> =
        withContext(Dispatchers.IO) {
            val folders = ArrayDeque<Pair<String, Int>>()
            folders.addLast(root.toString() to 0)
            val found = arrayListOf<HerrinSourceMovie>()
            var visited = 0
            while (folders.isNotEmpty() && visited < 16 && found.size < 30) {
                val (directory, depth) = folders.removeFirst()
                visited++
                currentCoroutineContext().ensureActive()
                // Cancellation of the selected TMDB title must interrupt
                // an old blocking NAS PROPFIND instead of stalling next title.
                val children = try { list(directory) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error:SourceHttpFailure){throw error}
                catch (_: Exception) { emptyList() }
                for (child in children.take(250)) {
                    if (child.isCollection) {
                        if (depth < 2) {
                            // Collection hrefs may omit '/', making relative
                            // grandchildren resolve against the wrong parent.
                            val collection = child.address.substringBefore('?').trimEnd('/') + "/" +
                                (if ('?' in child.address) "?" + child.address.substringAfter('?') else "")
                            if (sameOriginUnderRoot(collection))
                                folders.addLast(collection to depth + 1)
                        }
                        continue
                    }
                    val filename = URI(child.address).path.substringAfterLast('/')
                    if (filename.substringAfterLast('.', "").lowercase(Locale.ROOT) !in extensions)
                        continue
                    val rawTitle = filename.substringBeforeLast('.')
                    val match = seasonEp.find(rawTitle)
                    val season = match?.groupValues?.get(1)?.toIntOrNull()
                    val episode = match?.groupValues?.get(2)?.toIntOrNull()
                    val mediaType = if (match != null) "tv" else identity.mediaType
                    val sanitized = rawTitle.replace(seasonEp, " ")
                    val year = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""").find(sanitized)?.value.orEmpty()
                    val film = Film(
                        id = id + "-" + child.address.hashCode().toUInt().toString(16),
                        title = ArtworkTitle.searchQuery(sanitized),
                        subtitle = rawTitle.take(80),
                        category = if (mediaType == "tv") "剧集" else "电影",
                        year = year,
                        synopsis = "", artwork = R.drawable.photofilm_orbit,
                        playbackEpisodes = listOf(PlaybackEpisode(episode ?: 1,
                            if (episode == null) "正片" else "第 " + episode + " 集",
                            listOf(PlaybackLine(id, account.name, child.address))))
                    )
                    val entry = HerrinSourceMovie(
                        id, type, film, mediaType, season = season, episode = episode,
                        resolutionClaim = HerrinFilmIdentityMatch.resolutionClaim(rawTitle),
                        hdrClaim = Regex("""(?i)(?:^|[._ -])(?:HDR10|HDR|DV|Dolby.Vision)(?:$|[._ -])""")
                            .containsMatchIn(rawTitle)
                    )
                    if (HerrinFilmIdentityMatch.matches(identity, entry)) found += entry
                }
            }
            found
        }

    /** A selected direct file remains under the original, explicitly permitted DAV root. */
    fun playback(offer: HerrinSourceMovie): PlaybackResult {
        if (offer.sourceId != id || offer.sourceType != type)
            return PlaybackResult.Unavailable("媒体不是此 NAS/云盘来源")
        val line = offer.film.playbackEpisodes.firstOrNull()?.lines?.firstOrNull()
            ?: return PlaybackResult.Unavailable("媒体缺少可播放文件")
        if (!sameOriginUnderRoot(line.uri)) return PlaybackResult.Unavailable("媒体路径超出授权目录")
        return PlaybackResult.Ready(PlaybackMedia(
            uri = line.uri,
            headers = if (account.username.isBlank()) emptyMap() else
                mapOf("Authorization" to Credentials.basic(account.username, account.password)),
            allowHttpRedirects = false
        ))
    }

    private fun sameOriginUnderRoot(url: String): Boolean = runCatching {
        val target = URI(url).normalize()
        val origin = root.normalize()
        target.scheme.equals(origin.scheme, true) &&
            target.host.equals(origin.host, true) && target.port == origin.port &&
            target.rawUserInfo == null &&
            (target.path.orEmpty() + if (url.endsWith('/')) "/" else "")
                .startsWith(rootPrefix)
    }.getOrDefault(false)

    /** Depth:1 with strict 256 KiB response limit, same-origin hrefs and no redirects. */
    private suspend fun list(directory: String): List<DavEntry> {
        if (!sameOriginUnderRoot(directory) && directory != root.toString()) return emptyList()
        val xml = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
        val request = Request.Builder().url(directory)
            .method("PROPFIND", xml.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "1")
            .apply {
                if (account.username.isNotBlank())
                    header("Authorization", Credentials.basic(account.username, account.password))
            }.build()
        return readSourceResponse(client,request) { response ->
            if (response.code != 207) throw SourceHttpFailure(response.code,SourceHealthIndex.retryAfter(response.header("Retry-After"),System.currentTimeMillis()))
            val body = response.body ?: return@readSourceResponse emptyList()
            val input = body.byteStream()
            val buffer = ByteArray(8192)
            val output = java.io.ByteArrayOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (output.size() + n > 262144) return@readSourceResponse emptyList()
                output.write(buffer, 0, n)
            }
            // Android's platform DOM parser does not implement every Xerces
            // setFeature URI. Refusing ALL declarations before DOM parsing
            // makes the conservative parser policy work on Android TV as well
            // as desktop JVM without silently treating valid DAV as empty.
            val xmlText = output.toString(Charsets.UTF_8.name())
            if (Regex("""<!\s*(?:DOCTYPE|ENTITY)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(xmlText)) return@readSourceResponse emptyList()
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            // Some Android API levels throw ParserConfigurationException for
            // Xerces security features. DTDs are already refused above.
            runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            runCatching { factory.isXIncludeAware = false }
            factory.setExpandEntityReferences(false)
            val document = factory.newDocumentBuilder()
                .parse(InputSource(StringReader(xmlText)))
            val results = document.getElementsByTagNameNS("DAV:", "response")
            (0 until minOf(250, results.length)).mapNotNull { index ->
                val element = results.item(index) as? Element ?: return@mapNotNull null
                val href = element.getElementsByTagNameNS("DAV:", "href").item(0)
                    ?.textContent?.trim() ?: return@mapNotNull null
                val absolute = URI(directory).resolve(href).normalize().toString()
                if (absolute == directory || !sameOriginUnderRoot(absolute)) return@mapNotNull null
                val collection = element.getElementsByTagNameNS("DAV:", "collection").length > 0
                DavEntry(absolute, collection)
            }
        }
    }
}
