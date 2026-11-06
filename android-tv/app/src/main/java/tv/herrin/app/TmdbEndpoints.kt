package tv.herrin.app

import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI

/** API health is independent of image.tmdb.org. Never expose credentials in diagnostics. */
internal object TmdbEndpoints {
    const val PRIMARY = "https://api.tmdb.org/3"
    const val FALLBACK = "https://api.themoviedb.org/3"
    private val router = TmdbEndpointRouter()
    fun load(url: String, request: (String) -> String?): String? = router.load(url, request)
    val current: String get() = router.current
}

/** One bounded failover; shared cooldown avoids repeatedly timing out the home catalog. */
internal class TmdbEndpointRouter(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    @Volatile private var fallbackUntil = 0L
    val current: String get() = if (clock() < fallbackUntil) TmdbEndpoints.FALLBACK else TmdbEndpoints.PRIMARY
    fun load(url: String, request: (String) -> String?): String? {
        val parsed = URI(url)
        require(parsed.scheme == "https" && parsed.host in setOf("api.tmdb.org", "api.themoviedb.org") &&
            parsed.rawPath.startsWith("/3/") && parsed.rawUserInfo == null && parsed.port == -1)
        val suffix = parsed.rawPath.removePrefix("/3") + (parsed.rawQuery?.let { "?$it" } ?: "")
        val selected = current
        try {
            return request(selected + suffix)
        } catch (error: IOException) {
            if (error is ArtworkAuthenticationFailure || error is ArtworkRateLimitFailure) throw error
            if (Thread.currentThread().isInterrupted || error is InterruptedIOException &&
                error !is java.net.SocketTimeoutException) throw error
            if (error is ArtworkHttpStatusFailure && error.status !in setOf(500, 502, 503, 504)) throw error
            // During fallback cooldown do not retry the failed primary on each request.
            if (selected == TmdbEndpoints.FALLBACK) throw error
            fallbackUntil = clock() + 60_000L
            return request(TmdbEndpoints.FALLBACK + suffix)
        }
    }
}
