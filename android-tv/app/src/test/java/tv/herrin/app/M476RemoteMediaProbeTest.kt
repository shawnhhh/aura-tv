package tv.herrin.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import tv.herrin.app.nativeplayback.FongMiSessionAuthorization
import tv.herrin.app.nativeplayback.NativeFongMiProxy
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

class M476RemoteMediaProbeTest {
    @Test fun slowChunkedBinaryStopsAtConclusiveSignatureWithoutWaitingForOneKilobyte(): Unit = runBlocking {
        for (prefix in listOf(byteArrayOf(0,0,0,24,102,116,121,112), byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte()))) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream")
                    .setChunkedBody(okio.Buffer().write(prefix).write(ByteArray(2048)), prefix.size)
                    .throttleBody((prefix.size + prefix.size.toString(16).length + 4).toLong(), 2, java.util.concurrent.TimeUnit.SECONDS))
                server.start()
                val began = System.nanoTime()
                assertEquals(HerrinRemoteMediaProbe.Result.MEDIA, probe().probe(PlaybackMedia(server.url("/signed").toString())))
                assertTrue("Must not wait for the next chunk or enlarge the timeout", (System.nanoTime()-began)/1_000_000 < 1200)
            }
        }
    }

    private fun probe(authorized: Boolean = true) = HerrinRemoteMediaProbe(
        authorizeLocal = { authorized })

    @Test fun defaultLocalProbeIsDeniedWithoutExplicitApprovedSpiderSession() = runBlocking {
        val sends = java.util.concurrent.atomic.AtomicInteger()
        tv.herrin.app.nativeplayback.NativeFongMiProxy("approved", { _ ->
            sends.incrementAndGet()
            arrayOf(200, "video/mp4",
                java.io.ByteArrayInputStream(byteArrayOf(0, 0, 0, 24, 1, 2, 3, 4)))
        }, null).use { native ->
            assertEquals("Unscoped media probes must never trust a localhost token alone",
                HerrinRemoteMediaProbe.Result.UNAUTHORIZED_LOCAL,
                HerrinRemoteMediaProbe().probe(PlaybackMedia(native.siteUrl())))
            assertEquals("Unscoped probe must never access the Spider byte callback",
                0, sends.get())
        }
    }

    @Test fun actuallyReadsVideoBytesAndPreservesOnlyCurrentSourceAuth() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-15/8192")
                .setBody("0123456789ABCDEF"))
            server.start()
            val url = server.url("/film.mp4").toString()
            assertEquals(HerrinRemoteMediaProbe.Result.MEDIA,
                probe().probe(PlaybackMedia(url,
                    mapOf("Authorization" to "Bearer authorized-only"))))
            val sent = server.takeRequest()
            assertEquals("GET", sent.method)
            assertEquals("bytes=0-1023", sent.getHeader("Range"))
            assertEquals("Bearer authorized-only", sent.getHeader("Authorization"))
        }
    }

    @Test fun rejectsEmptyHtmlLoginAndForgedPartialContent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html").setBody("<html>login</html>"))
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "video/mp4").setBody(""))
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Range", "bytes 3-4/900").setBody("xx"))
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("<!DOCTYPE html>login"))
            server.start()
            val media = PlaybackMedia(server.url("/media.mp4").toString())
            assertEquals(HerrinRemoteMediaProbe.Result.LOGIN_OR_TEXT, probe().probe(media))
            assertEquals(HerrinRemoteMediaProbe.Result.EMPTY_BODY, probe().probe(media))
            assertEquals(HerrinRemoteMediaProbe.Result.INVALID_RANGE, probe().probe(media))
            assertEquals(HerrinRemoteMediaProbe.Result.LOGIN_OR_TEXT, probe().probe(media))
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun hlsMustContainActualManifestNotAnHtmlErrorPage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10,\nchunk.ts\n"))
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("Your payment is required"))
            server.start()
            val media = PlaybackMedia(server.url("/master.m3u8").toString())
            assertEquals(HerrinRemoteMediaProbe.Result.MEDIA, probe().probe(media))
            assertEquals(HerrinRemoteMediaProbe.Result.INVALID_MANIFEST, probe().probe(media))
            assertNull(server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun malformedPartialContentMustNotQualifyAsReadableMedia() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-not-a-number/8192")
                .setBody("0123456789ABCDEF"))
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-15/8")
                .setBody("0123456789ABCDEF"))
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-15/*")
                .setBody("0123456789ABCDEF"))
            server.start()
            val media = PlaybackMedia(server.url("/real-media.mp4").toString())
            assertEquals(HerrinRemoteMediaProbe.Result.INVALID_RANGE, probe().probe(media))
            assertEquals(HerrinRemoteMediaProbe.Result.INVALID_RANGE, probe().probe(media))
            assertEquals(HerrinRemoteMediaProbe.Result.MEDIA, probe().probe(media))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun partialResponseMustNotClaimBytesBeyondProbeRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-1024/8192")
                .setBody("x".repeat(1025)))
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-1023/8192")
                .setBody("x".repeat(1024)))
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-15/*")
                .setBody("0123456789ABCDEF"))
            server.start()
            val media = PlaybackMedia(server.url("/media.mp4").toString())
            assertEquals(HerrinRemoteMediaProbe.Result.INVALID_RANGE, probe().probe(media))
            assertEquals(HerrinRemoteMediaProbe.Result.MEDIA, probe().probe(media))
            assertEquals(HerrinRemoteMediaProbe.Result.MEDIA, probe().probe(media))
            assertEquals(3, server.requestCount)
            repeat(3) {
                assertEquals("bytes=0-1023", server.takeRequest().getHeader("Range"))
            }
        }
    }

    @Test fun jsonStatusBodyIsNotPlayableMedia() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("""{"status":"unavailable","reason":"try later"}"""))
            server.start()
            val media = PlaybackMedia(server.url("/film.mp4").toString())
            assertEquals(HerrinRemoteMediaProbe.Result.LOGIN_OR_TEXT, probe().probe(media))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun approvedFongMiSpiderStreamQualifiesForGlobalSourceLookupWithoutLegacyGate() = runBlocking {
        val byteReads = AtomicInteger()
        NativeFongMiProxy("approved", { params ->
            assertEquals("approved", params["siteKey"])
            assertEquals("jar", params["do"])
            assertFalse("Probe must preserve FongMi manifest dispatch, without injected Range", params.containsKey("range"))
            byteReads.incrementAndGet()
            arrayOf(200, "video/mp4", ByteArrayInputStream(byteArrayOf(0, 0, 0, 24, 1, 2, 3, 4)))
        }, null).use { nativeProxy ->
            val url = nativeProxy.siteUrl()
            assertTrue(FongMiSessionAuthorization.isApproved(url, "approved"))
            val globalSelectorProbe = HerrinRemoteMediaProbe(authorizeLocal = { candidate ->
                FongMiSessionAuthorization.isApproved(candidate, "approved")
            })
            assertEquals(HerrinRemoteMediaProbe.Result.MEDIA,
                globalSelectorProbe.probe(PlaybackMedia(url)))
            assertEquals(1, byteReads.get())

            val wrongSiteProbe = HerrinRemoteMediaProbe(authorizeLocal = { candidate ->
                FongMiSessionAuthorization.isApproved(candidate, "other-site")
            })
            assertEquals(HerrinRemoteMediaProbe.Result.UNAUTHORIZED_LOCAL,
                wrongSiteProbe.probe(PlaybackMedia(url)))
            val noActiveSpiderProbe = HerrinRemoteMediaProbe(authorizeLocal = { candidate ->
                FongMiSessionAuthorization.isApproved(candidate, null)
            })
            assertEquals(HerrinRemoteMediaProbe.Result.UNAUTHORIZED_LOCAL,
                noActiveSpiderProbe.probe(PlaybackMedia(url)))
            assertFalse(FongMiSessionAuthorization.isApproved(
                url.replace(nativeProxy.sessionToken(), "stale"), "approved"))
            assertFalse(FongMiSessionAuthorization.isApproved(
                "http://127.0.0.1:" + nativeProxy.port() + "/unrelated?session=" +
                    nativeProxy.sessionToken(), "approved"))
            assertEquals("No source data leaks to unauthorized candidates", 1, byteReads.get())
        }
    }

    @Test fun localProxyNeedsCurrentSessionAndRedirectNeverTargetsLoopback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("video"))
            server.start()
            val raw = server.url("/not-approved").toString()
            assertEquals(HerrinRemoteMediaProbe.Result.UNAUTHORIZED_LOCAL,
                probe(authorized = false).probe(PlaybackMedia(raw)))
            assertEquals(0, server.requestCount)
        }
        MockWebServer().use { source ->
            source.enqueue(MockResponse().setResponseCode(302)
                .setHeader("Location", "http://127.0.0.1:9987/private"))
            source.start()
            assertEquals(HerrinRemoteMediaProbe.Result.INVALID_URL,
                probe().probe(PlaybackMedia(source.url("/redirect").toString(),
                    mapOf("Authorization" to "private"))))
            assertEquals(1, source.requestCount)
        }
    }
    @Test fun fragmentedHlsHeaderIsReadAsBytesNotRejectedAfterFirstPacket() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:1,\nchunk.ts\n")
                .throttleBody(1, 2, java.util.concurrent.TimeUnit.MILLISECONDS))
            server.start()
            assertEquals(HerrinRemoteMediaProbe.Result.MEDIA,
                probe().probe(PlaybackMedia(server.url("/fragmented.m3u8").toString())))
        }
    }

}
