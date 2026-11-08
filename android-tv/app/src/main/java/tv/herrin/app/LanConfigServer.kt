package tv.herrin.app

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Ephemeral, locally bound configuration. Cleartext exposes only public source editing. */
internal class LanConfigServer(
    private val context: Context, val host: String,
    val quickHttp: Boolean,
    private val applied: (String) -> Unit
) : NanoHTTPD(host, 0) {
    constructor(context: Context, host: String, applied: (String) -> Unit) :
        this(context, host, false, applied)
    val pairing = ConfigPairingSession(maxLifetimeMs = if (quickHttp) 5 * 60_000L else Long.MAX_VALUE)
    private val reaper = java.util.Timer("lan-config-expiry", true)
    internal val certificate = HeldCertificate.Builder().commonName("HERRIN local configuration")
        .addSubjectAlternativeName(host).build()
    val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded)
        .joinToString(":") { "%02X".format(it) }
    val origin: String get() = "${if (quickHttp) "http" else "https"}://$host:$listeningPort"
    val qr: String get() = "$origin/#${pairing.pairing}"
    init {
        if (quickHttp) {
            val address = java.net.InetAddress.getByName(host)
            require(address is java.net.Inet4Address && (address.isSiteLocalAddress || address.isLoopbackAddress))
        } else {
            val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            makeSecure(tls.sslContext().serverSocketFactory, null)
        }
        reaper.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() { if (pairing.expired()) end() }
        }, 250L, 250L)
    }
    override fun serve(session: IHTTPSession): Response {
        fun response(code: Response.Status, body: String, type: String = "application/json"): Response =
            newFixedLengthResponse(code, type, body).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("X-Content-Type-Options", "nosniff")
                addHeader("Referrer-Policy", "no-referrer")
                addHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'")
            }
        if (session.headers["host"] != "$host:$listeningPort" || pairing.expired())
            return response(Response.Status.FORBIDDEN, "{}")
        if (session.method == Method.GET && session.uri in setOf("/", "/center.js", "/center.css")) {
            val file = when (session.uri) { "/" -> "center.html"; else -> session.uri.drop(1) }
            val type = when (file) { "center.html" -> "text/html; charset=utf-8"; "center.js" -> "application/javascript"; else -> "text/css" }
            return response(Response.Status.OK, context.assets.open("config/${if (quickHttp && file != "center.css") file.replace("center", "quick") else file}").bufferedReader().use { it.readText() }, type)
        }
        if (session.method != Method.POST || session.headers["origin"] != origin ||
            session.headers["content-type"]?.substringBefore(';') != "application/json" ||
            session.headers.containsKey("transfer-encoding")) return response(Response.Status.FORBIDDEN, "{}")
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length == null || length !in 1..32768) return response(Response.Status.BAD_REQUEST, "{}")
        return try {
            val body = HashMap<String, String>()
            session.parseBody(body)
            val input = JSONObject(body["postData"].orEmpty())
            if (quickHttp) QuickConfigPolicy.validate(session.uri, input)
            if (session.uri == "/pair") {
                val key = input.optString("pairing")
                if (!pairing.request(key)) return response(Response.Status.FORBIDDEN, "{}")
                val grant = pairing.claim(key)
                return response(Response.Status.OK, JSONObject().put("pending", grant == null)
                    .put("session", grant ?: "").toString())
            }
            if (!pairing.authorized(session.headers["x-herrin-session"].orEmpty()))
                return response(Response.Status.FORBIDDEN, "{}")
            val prefs = Preferences(context)
            val extra = AdditionalServiceConfigs(context)
            if (session.uri in setOf("/entries", "/entries/save", "/entries/delete")) {
                val module = input.getString("module")
                require(module in setOf("sources", "live", "nas"))
                if (quickHttp && input.optString("id").isNotBlank())
                    require(extra.list(module).any { it.id == input.getString("id") && QuickConfigPolicy.visible(it) })
                if (session.uri == "/entries/save") extra.save(module, input)
                if (session.uri == "/entries/delete") extra.remove(module, input.getString("id"))
                if (session.uri != "/entries") applied(module)
                return response(Response.Status.OK, JSONObject().put("entries", if (quickHttp) org.json.JSONArray().apply {
                    extra.list(module).filter(QuickConfigPolicy::visible).forEach {
                        put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("enabled", it.enabled))
                    }
                } else extra.publicList(module)).toString())
            }
            val output = when (session.uri) {
                "/catalogs" -> JSONObject().put("entries", org.json.JSONArray(CatalogProfileCodec.encode(CatalogProfiles(context).list())))
                    .put("active", CatalogProfiles(context).activeId() ?: "")
                "/catalogs/save" -> {
                    val store = CatalogProfiles(context)
                    val id = input.optString("id")
                    val result = if (id.isBlank()) store.addAndActivate(input.getString("name"), input.getString("url"))
                        else store.update(id, input.getString("name"), input.getString("url"))
                    require(result is SaveProfileResult.Saved)
                    applied("catalogs"); JSONObject().put("ok", true)
                }
                "/catalogs/delete" -> {
                    require(CatalogProfiles(context).delete(input.getString("id")))
                    applied("catalogs"); JSONObject().put("ok", true)
                }
                "/catalogs/activate" -> {
                    require(CatalogProfiles(context).activate(input.optString("id").takeIf { it.isNotBlank() }))
                    applied("catalogs"); JSONObject().put("ok", true)
                }
                "/entries/batch" -> { extra.batchSources(input.getString("urls")); applied("sources"); JSONObject().put("ok", true) }
                "/nas/test" -> {
                    val id = input.optString("id")
                    val account = if (id.isBlank()) {
                        val login = HerrinPersonalCredentialStore(context, "nas").load()
                        HerrinWebDavAccount("NAS", prefs.nasWebDavUrl, login.first, login.second)
                    } else {
                        val entry = extra.list("nas").first { it.id == id }
                        val login = HerrinPersonalCredentialStore(context, "config-$id").load()
                        HerrinWebDavAccount(entry.name, entry.url, login.first, login.second)
                    }
                    val connected = kotlinx.coroutines.runBlocking { HerrinWebDavSource(account).testConnection() }
                    JSONObject().put("result", if (connected) "WebDAV 已连接" else "连接失败")
                }
                "/end" -> { pairing.close(); JSONObject().put("ok", true) }
                "/status" -> JSONObject().put("endpoint", TmdbEndpoints.current)
                    .put("tokenConfigured", prefs.tmdbReadToken.isNotBlank())
                    .put("tvbox", prefs.tvBoxWarehouseUrl).put("m3u", prefs.livePlaylistUrl)
                    .put("epg", prefs.liveEpgUrl).put("nas", prefs.nasWebDavUrl)
                    .put("nasProtocol", "WebDAV")
                "/tmdb/test" -> JSONObject().put("result", TmdbConnectivityProbe.checkDetailed(prefs.tmdbReadToken).text)
                "/tmdb" -> {
                    val token = input.getString("token").trim()
                    require(token.isEmpty() || TmdbTokenEntry.assemble(token, emptyList(), false) != null)
                    prefs.tmdbReadToken = token
                    applied("tmdb"); JSONObject().put("ok", true)
                }
                "/sources" -> {
                    val raw = input.getString("url")
                    val url = if (raw.isBlank()) "" else AdditionalServiceConfigs.sourceAddress(raw)
                    prefs.tvBoxWarehouseUrl = url
                    // This never grants permission to execute a new Spider JAR.
                    applied("sources"); JSONObject().put("ok", true)
                }
                "/live" -> {
                    val m3u = address(input.getString("m3u")); val epg = address(input.getString("epg"))
                    prefs.livePlaylistUrl = m3u; prefs.liveEpgUrl = epg
                    applied("live"); JSONObject().put("ok", true)
                }
                "/nas" -> {
                    val url = address(input.getString("url"))
                    if (url.isNotBlank()) HerrinWebDavSource(HerrinWebDavAccount("NAS", url))
                    require(HerrinPersonalCredentialStore(context, "nas").save(input.getString("username"), input.getString("password")))
                    prefs.nasWebDavUrl = url
                    applied("nas"); JSONObject().put("ok", true)
                }
                else -> return response(Response.Status.NOT_FOUND, "{}")
            }
            response(Response.Status.OK, output.toString())
        } catch (_: Exception) {
            // Exception messages may include a credential or source URL; never return/log them.
            response(Response.Status.BAD_REQUEST, "{\"error\":\"配置无效或保存失败\"}")
        }
    }
    private fun address(raw: String): String {
        if (raw.isBlank()) return ""
        require(raw.length <= 2048)
        val uri = URI(raw.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null)
        return uri.toASCIIString()
    }
    fun end() { pairing.close(); reaper.cancel(); stop() }
}
