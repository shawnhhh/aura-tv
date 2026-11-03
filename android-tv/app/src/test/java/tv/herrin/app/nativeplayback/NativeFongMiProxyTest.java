package tv.herrin.app.nativeplayback;

import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeFongMiProxyTest {
    @Test public void deliversRealVideoBytesFromApprovedSpider() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("site", params -> {
            calls.incrementAndGet();
            assertEquals("jar", params.get("do"));
            assertNull(params.get("session"));
            assertNull(params.get("siteKey"));
            return new Object[]{206, "video/mp4",
                new ByteArrayInputStream(new byte[]{1,2,3,4}),
                Map.of("Accept-Ranges", "bytes", "Content-Length", "999")};
        }, null)) {
            assertTrue(proxy.port() > 0);
            HttpURLConnection http = connection(proxy.siteUrl());
            http.setRequestProperty("Range", "bytes=0-3");
            assertEquals(206, http.getResponseCode());
            assertArrayEquals(new byte[]{1,2,3,4}, http.getInputStream().readAllBytes());
            assertEquals("bytes", http.getHeaderField("Accept-Ranges"));
            assertEquals(1, calls.get());
            http.disconnect();
        }
    }

    @Test public void rejectsUnauthorizedRequestsBeforeCallingSpider() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("approved", params -> {
            calls.incrementAndGet();
            return new Object[]{200, "video/mp4", new ByteArrayInputStream(new byte[]{5})};
        }, null)) {
            assertEquals(403, responseCode(proxy.baseUrl() + "&siteKey=other&do=jar"));
            assertEquals(403, responseCode("http://127.0.0.1:" + proxy.port() +
                "/proxy?session=wrong&siteKey=approved&do=jar"));
            assertEquals(403, responseCode(proxy.baseUrl() + "&do=jar"));
            assertEquals(204, responseCode("http://127.0.0.1:" + proxy.port() +
                "/health?session=" + proxy.sessionToken() + "&siteKey=approved"));
            assertEquals(0, calls.get());
        }
    }

    @Test public void authorizedStaticJarCanHandleAnOriginalDoOperation() throws Exception {
        AtomicInteger siteCalls = new AtomicInteger();
        AtomicInteger staticCalls = new AtomicInteger();
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("site", params -> {
            siteCalls.incrementAndGet();
            return null;
        }, params -> {
            staticCalls.incrementAndGet();
            assertEquals("parse", params.get("do"));
            return new Object[]{200, "video/mp2t",
                new ByteArrayInputStream(new byte[]{0x47, 1, 2})};
        })) {
            HttpURLConnection http = connection(proxy.baseUrl() + "&do=parse");
            assertEquals(200, http.getResponseCode());
            assertArrayEquals(new byte[]{0x47,1,2}, http.getInputStream().readAllBytes());
            assertEquals(0, siteCalls.get());
            assertEquals(1, staticCalls.get());
            http.disconnect();
        }
    }

    @Test public void recognizesOriginalNanoHttpdResponseAndMalformedArrays() {
        NanoHTTPD.Response original = NanoHTTPD.newFixedLengthResponse("ok");
        assertSame(original, NativeFongMiProxy.fromUpstreamResponse(new Object[]{original}));
        assertEquals(502, NativeFongMiProxy.fromUpstreamResponse(new Object[]{}).getStatus().getRequestStatus());
        assertEquals(502, NativeFongMiProxy.fromUpstreamResponse(new Object[]{200, "text/plain"}).getStatus().getRequestStatus());
    }

    private static HttpURLConnection connection(String url) throws Exception {
        HttpURLConnection http = (HttpURLConnection) URI.create(url).toURL().openConnection();
        http.setConnectTimeout(3000);
        http.setReadTimeout(3000);
        return http;
    }
    private static int responseCode(String url) throws Exception {
        HttpURLConnection http = connection(url);
        try { return http.getResponseCode(); } finally { http.disconnect(); }
    }
}
