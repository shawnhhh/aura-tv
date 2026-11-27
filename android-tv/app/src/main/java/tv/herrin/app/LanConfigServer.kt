package tv.herrin.app

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import okhttp3.tls.HandshakeCertificates
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Short-lived, locally bound HTTPS configuration; transport never grants Spider execution. */
internal class LanConfigServer(
    private val context: Context, val host: String,
    private val applied: (String) -> Unit
) : NanoHTTPD(host, 0) {
    val pairing = ConfigPairingSession(maxLifetimeMs = 30 * 60_000L)
    private val endingResponse = java.util.concurrent.atomic.AtomicBoolean(false)
    private val reaper by lazy { java.util.Timer("lan-config-expiry", true) }
    internal val certificate = LanTlsIdentity(context).load(host)
    val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded)
        .joinToString(":") { "%02X".format(it) }
    val origin: String get() = "https://$host:$listeningPort"
    val qr: String get() = "$origin/#${pairing.pairing}"
    init {
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        makeSecure(tls.sslContext().serverSocketFactory, null)
        reaper.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() { if (pairing.expired() && !endingResponse.get()) end() }
        }, 250L, 250L)
    }
    override fun serve(session: IHTTPSession): Response {
        fun response(code: Response.Status, body: String, type: String = "application/json; charset=utf-8",
                     closeAfterSend: Boolean = false): Response {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val result = if (closeAfterSend) object : Response(code, type,
                java.io.ByteArrayInputStream(bytes), bytes.size.toLong()) {
                override fun send(outputStream: java.io.OutputStream) {
                    // NanoHTTPD 2.3.1 finishes gzip/chunks and flushes inside send.
                    // Revoke immediately, but never let the reaper truncate /end.
                    try { super.send(outputStream) } finally { this@LanConfigServer.end() }
                }
            } else newFixedLengthResponse(code, type, body)
            return result.apply {
                addHeader("Cache-Control", "no-store")
                addHeader("X-Content-Type-Options", "nosniff")
                addHeader("Referrer-Policy", "no-referrer")
                addHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'")
            }
        }
        if (session.headers["host"] != "$host:$listeningPort" || pairing.expired())
            return response(Response.Status.FORBIDDEN, "{}")
        if (session.method == Method.GET && session.uri in setOf("/", "/center.js", "/center.css")) {
            val file = when (session.uri) { "/" -> "center.html"; else -> session.uri.drop(1) }
            val type = when (file) { "center.html" -> "text/html; charset=utf-8"; "center.js" -> "application/javascript; charset=utf-8"; else -> "text/css; charset=utf-8" }
            return response(Response.Status.OK, context.assets.open("config/$file").bufferedReader().use { it.readText() }, type)
        }
        if (session.method != Method.POST || session.headers["origin"] != origin ||
            session.headers["content-type"]?.substringBefore(';') != "application/json" ||
            session.headers.containsKey("transfer-encoding")) return response(Response.Status.FORBIDDEN, "{}")
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length == null || length !in 1..32768) return response(Response.Status.BAD_REQUEST, "{}")
        return try {
            // NanoHTTPD defaults JSON without a charset to US-ASCII. Read the
            // bounded entity explicitly as UTF-8, including Chinese names/IDN.
            val input = JSONObject(LanJsonBody.read(session.inputStream, length.toInt()))
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
                if (session.uri == "/entries/save") extra.save(module, input)
                if (session.uri == "/entries/delete") extra.remove(module, input.getString("id"))
                if (session.uri != "/entries") applied(module)
                return response(Response.Status.OK, JSONObject().put("entries", extra.publicList(module)).toString())
            }
            if (session.uri == "/personal" || session.uri.startsWith("/personal/")) {
                val result = PersonalLibraryApi(context).handle(session.uri, input)
                if (session.uri in setOf("/personal/save", "/personal/delete", "/personal/toggle", "/personal/folder",
                        "/personal/folder/delete", "/personal/match")) applied("nas")
                return response(Response.Status.OK, result.toString())
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
                "/smb" -> {
                    HerrinSmbConfigStore(context).save(input)
                    applied("nas");JSONObject().put("ok",true)
                }
                "/smb/test" -> {
                    val result=try {
                        val account=requireNotNull(HerrinSmbConfigStore(context).account())
                        val connected=kotlinx.coroutines.runBlocking {
                            HerrinSmbSessions().use{HerrinSmbSource(account,it).testConnection()}
                        }
                        if(connected)"SMB2/3 共享目录已连接" else "SMB 共享目录不存在或不可访问"
                    }catch(error:Exception){SmbFailure.message(error)}
                    JSONObject().put("result",result)
                }
                "/end" -> {
                    if (!endingResponse.compareAndSet(false, true))
                        return response(Response.Status.FORBIDDEN, "{}")
                    pairing.close()
                    return response(Response.Status.OK, "{\"ok\":true}", closeAfterSend = true)
                }
                "/status" -> JSONObject().put("endpoint", TmdbEndpoints.current)
                    .put("tokenConfigured", prefs.tmdbReadToken.isNotBlank())
                    .put("tvbox", prefs.tvBoxWarehouseDisplayUrl).put("m3u", prefs.livePlaylistUrl)
                    .put("epg", prefs.liveEpgUrl).put("nas", prefs.nasWebDavUrl)
                    .put("smb",HerrinSmbConfigStore(context).publicConfig()).put("nasProtocol", "WebDAV")
                "/tmdb/test" -> JSONObject().put("result", TmdbConnectivityProbe.checkDetailed(prefs.tmdbReadToken).text)
                "/tmdb" -> {
                    val token = input.getString("token").trim()
                    require(token.isEmpty() || TmdbTokenEntry.assemble(token, emptyList(), false) != null)
                    if (token.isNotBlank()) prefs.tmdbReadToken = token
                    require(prefs.tmdbReadToken.isNotBlank())
                    applied("tmdb"); JSONObject().put("ok", true)
                }
                "/sources/status" -> LanSourceImport(context).status(input.getString("importId"))
                "/sources" -> {
                    val raw = input.getString("url")
                    // Accept the same HTTP(S) TVBox address policy as the TV settings.
                    // Respond with a bounded code, never the URL, on malformed input.
                    val url = if (raw.isBlank()) "" else TvBoxUrl.normalize(raw, allowHttp = true)
                        ?: return response(Response.Status.BAD_REQUEST,
                            "{\"code\":\"SOURCE_URL_INVALID\"}")
                    val store = LanSourceImport(context)
                    val saved = try { store.save(url, raw.trim()) } catch (_: Exception) {
                        return response(Response.Status.INTERNAL_ERROR, "{\"code\":\"SOURCE_SAVE_FAILED\"}")
                    }
                    try { applied("sources") } catch (_: Exception) {
                        store.update(saved.id, "FAILED", "SOURCE_NOTIFY_FAILED")
                        return response(Response.Status.INTERNAL_ERROR, "{\"code\":\"SOURCE_NOTIFY_FAILED\"}")
                    }
                    JSONObject().put("ok", true).put("importId", saved.id).put("state", "SAVED")
                }
                "/live" -> {
                    val m3u = address(input.getString("m3u")); val epg = address(input.getString("epg"))
                    prefs.livePlaylistUrl = m3u; prefs.liveEpgUrl = epg
                    applied("live"); JSONObject().put("ok", true)
                }
                "/nas" -> {
                    val url = input.getString("url").trim()
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
            val code = if (session.uri == "/sources") "SOURCE_SAVE_OR_APPLY_FAILED"
                else "CONFIG_INVALID"
            response(Response.Status.BAD_REQUEST, JSONObject().put("code", code)
                .put("error", "配置无效或保存失败").toString())
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
