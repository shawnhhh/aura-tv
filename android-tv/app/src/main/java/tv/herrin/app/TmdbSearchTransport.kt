package tv.herrin.app

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Search has its own bounded HTTP queue so home metadata cannot starve interactive input. */
internal object TmdbSearchTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .dispatcher(Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 }).build()

    suspend fun load(url: String, token: String): String {
        val suffix = url.removePrefix(TmdbEndpoints.PRIMARY)
        require(suffix.startsWith("/search/multi?"))
        val endpoint = TmdbEndpoints.current
        return try { request(endpoint + suffix, token) }
        catch (error: IOException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (error is ArtworkAuthenticationFailure || error is ArtworkRateLimitFailure ||
                endpoint == TmdbEndpoints.FALLBACK || error is ArtworkHttpStatusFailure && error.status !in setOf(500,502,503,504)) throw error
            request(TmdbEndpoints.FALLBACK + suffix, token)
        }
    }

    private suspend fun request(url: String, token: String): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json")
            .header("Authorization", "Bearer $token").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("TMDB_NETWORK_FAILURE"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        when (it.code) {
                            401, 403 -> throw ArtworkAuthenticationFailure(it.code)
                            429 -> throw ArtworkRateLimitFailure()
                        }
                        if (it.code != 200) throw ArtworkHttpStatusFailure(it.code)
                        val body = it.body ?: throw IOException("TMDB_EMPTY_RESPONSE")
                        if (!body.contentType().toString().startsWith("application/json") || body.contentLength() > 1_000_000)
                            throw IOException("TMDB_INVALID_RESPONSE")
                        body.byteStream().use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 1_000_000) throw IOException("TMDB_RESPONSE_TOO_LARGE")
                                output.write(buffer, 0, count)
                            }
                            output.toString("UTF-8")
                        }
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}
