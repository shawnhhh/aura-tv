package tv.herrin.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Invoked only after HTTPS pairing and origin/session checks in LanConfigServer. */
internal class PersonalLibraryApi(context: Context) {
    private val library = PersonalLibrary(context)
    private val prefs = Preferences(context)
    fun handle(path: String, input: JSONObject): JSONObject {
        val id = input.optString("id")
        when(path) {
            "/personal/save" -> {
                // Validate candidate before committing; a failed edit preserves the existing connection.
                val address = input.optString("address").trim()
                val normalized = JSONObject(input.toString())
                if (input.optString("protocol") != "smb" && address.isNotBlank()) {
                    val parsed = PersonalStoragePolicy.webDavAddress(address, input.optString("port"))
                    normalized.put("protocol", parsed.scheme).put("host", parsed.host)
                        .put("port", parsed.port).put("basePath", parsed.basePath)
                    if (PersonalStoragePolicy.path(normalized.optString("path")) == parsed.basePath && parsed.basePath.isNotEmpty())
                        normalized.put("path", "")
                }
                val protocol = normalized.getString("protocol")
                val old = id.takeIf { it.isNotBlank() }?.let { library.connections.get(it) }
                val host = PersonalStoragePolicy.host(normalized.getString("host"))
                val port = normalized.getInt("port")
                PersonalStoragePolicy.endpoint(protocol, host, port)
                val same = old?.takeIf { it.protocol == protocol && it.host.equals(host, true) && it.port == port && it.username == input.optString("username") && it.domain == input.optString("domain") }
                val candidate = PersonalConnections.Connection(id, normalized.getString("name"), protocol, host, port,
                    normalized.optString("username"), normalized.optString("password").ifEmpty { same?.password.orEmpty() },
                    normalized.optString("domain"), normalized.optBoolean("encryption"), normalized.optBoolean("enabled", true),
                    PersonalStoragePolicy.path(normalized.optString("path")),
                    PersonalStoragePolicy.path(normalized.optString("basePath")))
                val folder = PersonalStoragePolicy.path(normalized.optString("path"))
                require(folder.isNotEmpty() || candidate.basePath.isNotEmpty()) { "MEDIA_FOLDER_REQUIRED" }
                val available = try { runBlocking { PersonalRemoteFiles.list(candidate, folder) } }
                catch(error: Exception) { return JSONObject().put("ok", false).put("error", PersonalRemoteFiles.message(error)) }
                library.cancelAndWait()
                val saved = library.connections.save(normalized)
                library.connections.status(saved.id, "connected")
                // "Add" validates and imports precisely the chosen subtree.
                // Root enumeration is not implicit consent to scan an entire drive.
                val folderId = library.index.folder(saved.id, folder, "auto")
                library.refresh(folderId)
                val directories = available.filter { it.directory }.sortedBy { it.path }
                return JSONObject().put("ok", true).put("id", saved.id).put("path", folder).put("folderId", folderId).put("total", directories.size)
                    .put("files", JSONArray().apply { directories.take(100).forEach { put(JSONObject().put("name", it.name).put("path", it.path)) } })
            }
            "/personal/delete" -> {
                require(input.optBoolean("confirmed"))
                library.cancelAndWait()
                library.index.folders(id).forEach { library.index.removeFolder(it.id) }
                library.connections.delete(id)
            }
            "/personal/toggle" -> {
                library.cancelAndWait()
                val old = requireNotNull(library.connections.get(id))
                library.connections.save(old.publicJson().put("password", old.password).put("enabled", !old.enabled))
            }
            "/personal/browse", "/personal/test" -> {
                val c = requireNotNull(library.connections.get(id))
                val raw = if(input.has("path")) input.getString("path") else c.rootPath
                try {
                    val files = runBlocking { PersonalRemoteFiles.list(c, raw) }
                    library.connections.status(id, "connected")
                    val directories = files.filter { it.directory }.sortedBy { it.path }
                    val offset = input.optInt("offset").coerceIn(0, 1000000)
                    return JSONObject().put("ok", true).put("files", JSONArray().apply {
                        directories.drop(offset).take(100).forEach { put(JSONObject().put("path", it.path).put("name", it.name)) }
                    }).put("total", directories.size).put("result", "连接成功")
                } catch(error: Exception) { library.connections.status(id, "failed"); return JSONObject().put("ok", false).put("error", PersonalRemoteFiles.message(error)) }
            }
            "/personal/folder" -> {
                requireNotNull(library.connections.get(input.getString("connection")))
                library.cancelAndWait()
                val c = requireNotNull(library.connections.get(input.getString("connection")))
                if(c.protocol == "smb" && input.getString("path").isBlank())
                    return JSONObject().put("ok", false).put("error", "请输入 SMB 共享名称后导入媒体目录")
                val folder = library.index.folder(input.getString("connection"), input.getString("path"), input.getString("type"), input.optBoolean("enabled", true))
                if(input.optBoolean("scan", true)) library.refresh(folder)
            }
            "/personal/folder/delete" -> { require(input.optBoolean("confirmed")); library.cancelAndWait(); library.index.removeFolder(id) }
            "/personal/refresh" -> library.refresh(id)
            "/personal/cancel" -> library.cancelAndWait()
            "/personal/search" -> {
                val discovery = HerrinTmdbDiscovery()
                val matches = runBlocking { discovery.search(input.getString("query").take(200), prefs.tmdbReadToken) }
                return JSONObject().put("ok", true).put("matches", JSONArray().apply {
                    matches.films.forEach { film -> discovery.identity(film)?.let { (type, id) ->
                        put(JSONObject().put("tmdb", id).put("type", type).put("title", film.title).put("year", film.year).put("poster", film.artworkUrl).put("synopsis", film.synopsis))
                    } }
                })
            }
            "/personal/match" -> {
                library.index.correct(id, input.getInt("tmdb"), input.getString("type"), input.getString("title"), input.optString("year").toIntOrNull(), input.optString("poster"), input.optString("synopsis"))
                PersonalLibrary.metadataChanged()
            }
            "/personal" -> Unit
            else -> error("UNKNOWN_PERSONAL_OPERATION")
        }
        return JSONObject().put("ok", true).put("migrationWarning", library.connections.migrationWarning()).put("connections", library.connections.publicList())
            .put("folders", JSONArray().apply { library.index.folders().forEach { f -> put(JSONObject().put("id", f.id).put("connection", f.connection).put("path", f.path).put("type", f.type).put("enabled", f.enabled).put("state", f.state).put("scanned", f.scanned)) } })
            .put("media", library.index.media(limit = 100, offset = input.optInt("offset"), category = input.optString("category", "all"))).put("progress", PersonalLibrary.progress)
    }
}
