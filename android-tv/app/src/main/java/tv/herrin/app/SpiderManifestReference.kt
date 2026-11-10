/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.herrin.app.nativeplayback.NativeFongMiHls
import tv.herrin.app.nativeplayback.NativeFongMiProxy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Resolve a URL-only HLS callback inside the SAME approved native proxy.
 * No credentials are forwarded from the loopback request. Redirects and nested
 * references remain on the URL's explicitly supplied origin; public DNS only.
 */
internal class SpiderManifestReference(
    private val diagnostic: (String) -> Unit,
    private val client: OkHttpClient = OkHttpClient.Builder().dns(MediaProxyPublicDns())
        .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).build()
) : NativeFongMiProxy.ManifestResolver {
    private val calls = mutableSetOf<Call>()
    private var closed = false

    private fun record(code: String) { runCatching { diagnostic(code) } }

    override fun load(reference: String, headers: Map<String, String>): String {
        // DefaultHttpDataSource's session properties also apply to child URLs.
        // Do not introduce a new remote origin for origin-specific credentials.
        if (headers.keys.any { it.lowercase() in setOf("cookie", "authorization", "origin", "referer") })
            throw IOException("HLS_REFERENCE_CREDENTIAL_ORIGIN_UNKNOWN").also { record("HLS_REFERENCE_CREDENTIAL_ORIGIN_UNKNOWN") }
        val policy = MediaProxyPolicy.authorize(reference)
        var target = policy.source
        record(if(target.isHttps) "HLS_REFERENCE_TARGET_HTTPS" else "HLS_REFERENCE_TARGET_HTTP")
        repeat(3) { hop ->
            val call = client.newCall(Request.Builder().url(target).get().apply {
                headers.filterKeys { it.lowercase() in setOf("user-agent", "accept", "accept-language") }
                    .forEach { (name, value) -> header(name, value) }
                header("Accept-Encoding", "identity")
            }.build())
            synchronized(calls) { check(!closed); calls += call }
            try {
                call.execute().use { response ->
                    record("HLS_REFERENCE_HTTP_" + response.code.coerceIn(100, 599))
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        if (hop == 2) throw IOException("HLS reference redirect limit")
                        target = policy.resolve(target, response.header("Location") ?: throw IOException("Missing HLS redirect"))
                    } else {
                        if (response.code != 200) {
                            if (response.code in setOf(401, 403)) {
                                if (response.header("WWW-Authenticate") != null) record("HLS_REFERENCE_WWW_AUTHENTICATE")
                                val bytes=ByteArray(2048)
                                val count=response.body?.byteStream()?.read(bytes) ?: -1
                                record(denialCode(if(count>0) String(bytes,0,count,Charsets.UTF_8) else ""))
                            }
                            throw IOException("HLS reference HTTP error")
                        }
                        val stream = response.body?.byteStream() ?: throw IOException("Missing HLS body")
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            if (output.size() + n > NativeFongMiHls.MAX_MANIFEST_BYTES) throw IOException("HLS reference size limit")
                            output.write(buffer, 0, n)
                        }
                        val text = output.toString(Charsets.UTF_8.name())
                        // Resolve against the final same-origin manifest location,
                        // including relative media, key, map and child-playlist URIs.
                        return NativeFongMiHls.resolveReferences(text, target.toString())
                            ?: throw IOException("HLS reference manifest invalid").also {
                                record(NativeFongMiHls.rejectionCode(text))
                            }
                    }
                }
            } finally { synchronized(calls) { calls -= call } }
        }
        throw IOException("HLS reference redirect limit")
    }

    companion object {
        /** Classify standard denial markers in memory; never export error text. */
        internal fun denialCode(body: String): String = when {
            body.contains("Only HTTPS requests are allowed", true) -> "HLS_REFERENCE_HTTPS_REQUIRED"
            body.contains("Domain forbidden", true) -> "HLS_REFERENCE_DOMAIN_POLICY"
            body.contains("<Code>ExpiredToken</Code>", true) ||
                body.contains("<Code>RequestExpired</Code>", true) -> "HLS_REFERENCE_SIGNATURE_EXPIRED"
            body.contains("<Code>SignatureDoesNotMatch</Code>", true) -> "HLS_REFERENCE_SIGNATURE_MISMATCH"
            body.contains("<Code>AccessDenied</Code>", true) -> "HLS_REFERENCE_ACCESS_DENIED"
            body.trimStart().startsWith("<html", true) || body.contains("<!doctype html", true) -> "HLS_REFERENCE_DENIAL_HTML"
            body.trimStart().startsWith("{") -> "HLS_REFERENCE_DENIAL_JSON"
            else -> "HLS_REFERENCE_DENIAL_UNSPECIFIED"
        }
    }

    override fun close() {
        synchronized(calls) { closed = true; calls.toList() }.forEach(Call::cancel)
    }
}
