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
            assertEquals("site", params.get("siteKey"));
            return new Object[]{206, "video/mp4",
                new ByteArrayInputStream(new byte[]{1,2,3,4}),
                Map.of("Accept-Ranges", "bytes", "Content-Length", "999")};
        }, null)) {
            AtomicInteger diagnosticStatus = new AtomicInteger();
            java.util.concurrent.atomic.AtomicBoolean diagnosticRange = new java.util.concurrent.atomic.AtomicBoolean();
            proxy.setDiagnostic((status, range) -> { diagnosticStatus.set(status); diagnosticRange.set(range); });
            assertTrue(proxy.port() > 0);
            HttpURLConnection http = connection(proxy.siteUrl());
            http.setRequestProperty("Range", "bytes=0-3");
            assertEquals(206, http.getResponseCode());
            assertArrayEquals(new byte[]{1,2,3,4}, http.getInputStream().readAllBytes());
            assertEquals("bytes", http.getHeaderField("Accept-Ranges"));
            assertEquals(1, calls.get());
            assertEquals(206, diagnosticStatus.get());
            assertTrue(diagnosticRange.get());
            http.disconnect();
        }
    }

    @Test public void diagnosticsAreBoundedAndCannotChangeHttpResponses() throws Exception {
        AtomicInteger reports = new AtomicInteger();
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("approved", params ->
                new Object[]{200, "video/mp4", new ByteArrayInputStream(new byte[]{5})}, null)) {
            proxy.setDiagnostic((status, range) -> {
                reports.incrementAndGet();
                throw new IllegalStateException("original diagnostic fixture");
            });
            for (int i = 0; i < 18; i++) assertEquals(200, responseCode(proxy.siteUrl()));
            assertEquals(16, reports.get());
            assertEquals(403, responseCode(proxy.baseUrl() + "&siteKey=other&do=jar"));
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

    @Test public void failureDiagnosticsCannotReplaceHlsRejection() throws Exception {
        java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (NativeFongMiProxy proxy = new NativeFongMiProxy("approved", params ->
                new Object[]{200, "application/vnd.apple.mpegurl", new ByteArrayInputStream(
                    "private provider error instead of a playlist".getBytes(java.nio.charset.StandardCharsets.UTF_8))}, null)) {
            proxy.setDiagnostic(new NativeFongMiProxy.Diagnostic() {
                @Override public void httpResponse(int status, boolean range) {}
                @Override public void failure(String code) {
                    events.add(code);
                    throw new AssertionError("recorder failed");
                }
            });
            assertEquals(502, responseCode(proxy.siteUrl()));
            assertTrue(events.contains("HLS_MANIFEST_REJECTED"));
            assertTrue(events.contains("PROXY_CALLBACK_OR_REWRITE_FAILED"));
        }
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
