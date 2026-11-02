/* SPDX-License-Identifier: GPL-3.0-only
 * HERRIN host adaptation for FongMi browser parser's Sniffer boundary.
 * URL patterns alone never establish media: inspect an actual bounded HTTP
 * response, preserving TLS and per-origin credentials. No buffering of media.
 */
package tv.herrin.app

import tv.herrin.app.fongmi.parse.MediaSniffer
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal object FongMiMediaProbe {
    private val client = OkHttpClient.Builder().followRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS).build()

    internal fun isMedia(contentType: String, prefix: ByteArray): Boolean =
        MediaSniffer.isMedia(contentType, prefix)

    fun probe(raw: String, headers: Map<String, String>, allowHttp: Boolean,
        calls: MutableSet<Call>): Boolean {
        var url = TvBoxUrl.normalize(raw, allowHttp) ?: return false
        repeat(5) {
            val request = Request.Builder().url(url).header("Range", "bytes=0-511")
            FongMiParsePlayback.safeHeaders(headers, raw, url).forEach { (name, value) -> request.header(name, value) }
            val call = client.newCall(request.build())
            calls.add(call)
            try {
                call.execute().use { response ->
                    if (response.code in 300..399) {
                        val target = response.header("Location") ?: return false
                        val resolved = response.request.url.resolve(target)?.toString() ?: return false
                        url = TvBoxUrl.normalize(resolved, allowHttp) ?: return false
                    } else {
                        if (!response.isSuccessful) return false
                        val body = response.body ?: return false
                        val source = body.source()
                        source.request(512)
                        val bytes = source.buffer.clone().readByteArray(minOf(source.buffer.size, 512))
                        return isMedia(response.header("Content-Type").orEmpty(), bytes)
                    }
                }
            } catch (_: Exception) {
                return false
            } finally {
                calls.remove(call)
            }
        }
        return false
    }
}
