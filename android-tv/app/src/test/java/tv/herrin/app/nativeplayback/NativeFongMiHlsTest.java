package tv.herrin.app.nativeplayback;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeFongMiHlsTest {
    @Test public void rewritesOldFixedPortSegmentsAndKeyUrisToLiveSession() throws Exception {
        AtomicInteger segments = new AtomicInteger();
        String playlist = "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-KEY:METHOD=AES-128,URI=\"proxy://do=jar&siteKey=approved&key=abc\"\n"
            + "#EXTINF:4.0,\n"
            + "http://127.0.0.1:9978/proxy?do=jar&siteKey=approved&segment=0\n"
            + "#EXT-X-ENDLIST\n";
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("approved", params -> {
            assertEquals("0", params.get("segment"));
            segments.incrementAndGet();
            return new Object[]{200, "video/mp2t",
                new ByteArrayInputStream(new byte[]{0x47, 1, 2})};
        }, params -> new Object[]{200, "application/vnd.apple.mpegurl",
            new ByteArrayInputStream(playlist.getBytes(StandardCharsets.UTF_8))})) {
            String manifestUrl = NativeFongMiRoute.url("proxy://", "approved", proxy);
            assertNotNull(manifestUrl);
            HttpURLConnection req = connection(manifestUrl);
            assertEquals(200, req.getResponseCode());
            String response = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("#EXTM3U"));
            assertFalse(response.contains(":9978/"));
            assertTrue(response.contains(proxy.baseUrl()));
            assertTrue(response.contains("URI=\"" + proxy.baseUrl()));
            req.disconnect();
            String segmentUrl = response.lines().filter(s -> s.startsWith("http://"))
                .findFirst().orElseThrow();
            HttpURLConnection segment = connection(segmentUrl);
            assertEquals(200, segment.getResponseCode());
            assertArrayEquals(new byte[]{0x47, 1, 2}, segment.getInputStream().readAllBytes());
            segment.disconnect();
            assertEquals(1, segments.get());
        }
    }

    @Test public void rejectsCrossSiteHlsWithoutLeakingOrForwardingUrl() throws Exception {
        String manifest = "#EXTM3U\n"
            + "http://127.0.0.1:9978/proxy?do=jar&siteKey=foreign&segment=0\n";
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("approved", p -> null, p ->
            new Object[]{200, "application/vnd.apple.mpegurl",
                new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8))})) {
            HttpURLConnection req = connection(proxy.baseUrl());
            assertEquals(502, req.getResponseCode());
            assertEquals("1", req.getHeaderField("X-TV-Proxy-Error"));
            req.disconnect();
        }
    }

    @Test public void rewritesBomPrefixedGenericMimeHlsAndServesRealSegments() throws Exception {
        String playlist = "\uFEFF#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXTINF:6.0,\n"
            + "http://127.0.0.1:9978/proxy?do=jar&siteKey=approved&segment=0\n"
            + "#EXT-X-ENDLIST\n";
        AtomicInteger segmentRequests = new AtomicInteger();
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("approved", params -> {
            assertEquals("0", params.get("segment"));
            segmentRequests.incrementAndGet();
            return new Object[]{200, "video/mp2t",
                new ByteArrayInputStream(new byte[]{0x47, 0x02, 0x03})};
        }, params -> new Object[]{200, "application/octet-stream",
            new ByteArrayInputStream(playlist.getBytes(StandardCharsets.UTF_8))})) {
            HttpURLConnection manifestRequest = connection(proxy.baseUrl());
            assertEquals(200, manifestRequest.getResponseCode());
            String rewritten = new String(
                manifestRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            manifestRequest.disconnect();
            assertTrue("Media3 HLS parser needs a canonical #EXTM3U first line",
                rewritten.startsWith("#EXTM3U"));
            assertFalse("BOM/generic-MIME HLS must not retain TVBox's stale 9978 port",
                rewritten.contains(":9978/"));
            assertTrue("Segments must use this approved Spider session",
                rewritten.contains(proxy.baseUrl()));
            String segmentUrl = rewritten.lines().filter(l -> l.startsWith("http://"))
                .findFirst().orElseThrow();
            HttpURLConnection segment = connection(segmentUrl);
            assertEquals(200, segment.getResponseCode());
            assertArrayEquals(new byte[]{0x47, 0x02, 0x03},
                segment.getInputStream().readAllBytes());
            segment.disconnect();
            assertEquals(1, segmentRequests.get());
        }
    }

    @Test public void doesNotModifyOrdinaryBinaryVideo() throws Exception {
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("approved", p ->
            new Object[]{200, "video/mp4",
                new ByteArrayInputStream(new byte[]{0, 0, 0, 24, 0, 0, 0, 1})}, null)) {
            HttpURLConnection req = connection(proxy.siteUrl());
            assertEquals(200, req.getResponseCode());
            assertArrayEquals(new byte[]{0,0,0,24,0,0,0,1}, req.getInputStream().readAllBytes());
            req.disconnect();
        }
    }

    private static HttpURLConnection connection(String url) throws Exception {
        HttpURLConnection req = (HttpURLConnection) URI.create(url).toURL().openConnection();
        req.setConnectTimeout(3000);
        req.setReadTimeout(3000);
        return req;
    }
}
