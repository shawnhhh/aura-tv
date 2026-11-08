package tv.herrin.app

import org.json.JSONObject
import java.net.URI

/** Fail closed before any preference reads/writes, including unknown fields. */
internal object QuickConfigPolicy {
    fun validate(path: String, input: JSONObject) {
        val fields = when (path) {
            "/pair" -> setOf("pairing")
            "/end" -> emptySet()
            "/sources" -> setOf("url")
            "/entries" -> setOf("module")
            "/entries/save" -> setOf("module", "id", "name", "url", "enabled")
            "/entries/batch" -> setOf("urls")
            else -> error("Unavailable over cleartext")
        }
        require(input.keys().asSequence().all { it in fields })
        if (path.startsWith("/entries") && path != "/entries/batch")
            require(input.getString("module") == "sources")
        if (input.has("url")) publicAddress(input.getString("url"))
        if (input.has("urls")) {
            val urls = input.getString("urls")
            require(urls.length in 1..32768)
            val lines = urls.lineSequence().filter { it.isNotBlank() }.toList()
            require(lines.size in 1..10)
            lines.forEach(::publicAddress)
        }
    }

    fun publicAddress(raw: String): String {
        val url = AdditionalServiceConfigs.sourceAddress(raw)
        val uri = URI(url)
        // Public, credential-free endpoints only. Query strings may carry tokens,
        // including vendor-specific names, so none are accepted over cleartext.
        require(uri.rawQuery == null)
        require(!uri.path.contains(Regex("(?i)(token|password|passwd|secret|credential|auth|apikey|api_key)")))
        return url
    }

    fun visible(entry: AdditionalServiceConfigs.Entry): Boolean =
        runCatching { publicAddress(entry.url) }.isSuccess
}
