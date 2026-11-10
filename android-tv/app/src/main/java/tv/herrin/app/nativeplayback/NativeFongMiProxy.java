/* SPDX-License-Identifier: GPL-3.0-only
 * Adapted from FongMi/TV fongmi@305dff233cde512e2c83f71529b7c3ecfd61fefc
 * app/src/main/java/com/fongmi/android/tv/server/process/Proxy.java.
 * Host changes: bound 127.0.0.1 socket, 192-bit per-session capability,
 * approved-site isolation, and injected authorized Spider callbacks.
 */
package tv.herrin.app.nativeplayback;

import fi.iki.elonen.NanoHTTPD;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PushbackInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FongMi's HTTP proxy response model for one approved Java Spider runtime.
 * The server forwards raw do operations to THAT approved Spider; it never
 * loads arbitrary plugins and does not whitelist guessed operation names.
 */
public final class NativeFongMiProxy extends NanoHTTPD implements AutoCloseable {
    public interface SpiderCallback {
        Object[] proxy(Map<String, String> arguments) throws Exception;
    }

    private final String siteKey;
    private final String token;
    private final SpiderCallback siteProxy;
    private final SpiderCallback staticJarProxy;
    private volatile java.util.List<StaticOrigin> inheritedStaticOrigins = java.util.List.of();

    /** In-memory capabilities issued by this exact approved JAR/grant, never URLs from config. */
    public static final class StaticOrigin {
        private final int port;
        private final String token;
        private StaticOrigin(int port, String token) { this.port = port; this.token = token; }
        @Override public String toString() { return "StaticOrigin([redacted])"; }
    }

    public void inheritStaticOrigins(java.util.List<StaticOrigin> origins) {
        if (staticJarProxy == null) throw new IllegalStateException("Static JAR handler required");
        inheritedStaticOrigins = java.util.List.copyOf(origins);
    }

    public java.util.List<StaticOrigin> staticOrigins() {
        if (staticJarProxy == null) return java.util.List.of();
        java.util.List<StaticOrigin> result = new java.util.ArrayList<>(inheritedStaticOrigins);
        result.add(new StaticOrigin(port(), token));
        return java.util.List.copyOf(result);
    }

    boolean ownsRetiredStaticOrigin(int port, String supplied) {
        if (staticJarProxy == null || supplied == null) return false;
        for (StaticOrigin origin : inheritedStaticOrigins) {
            if (origin.port == port && MessageDigest.isEqual(
                    origin.token.getBytes(StandardCharsets.US_ASCII),
                    supplied.getBytes(StandardCharsets.US_ASCII))) return true;
        }
        return false;
    }

    public NativeFongMiProxy(String siteKey, SpiderCallback siteProxy,
                             SpiderCallback staticJarProxy) throws IOException {
        super("127.0.0.1", 0);
        if (siteKey == null || siteKey.trim().isEmpty() || siteKey.length() > 128 ||
            siteProxy == null) throw new IllegalArgumentException("Approved Spider required");
        this.siteKey = siteKey;
        this.siteProxy = siteProxy;
        this.staticJarProxy = staticJarProxy;
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        start(SOCKET_READ_TIMEOUT, true);
    }

    public int port() { return getListeningPort(); }
    public String sessionToken() { return token; }
    public boolean hasStaticHandler() { return staticJarProxy != null; }
    public String baseUrl() {
        return "http://127.0.0.1:" + port() + "/proxy?session=" + token;
    }
    public String siteUrl() {
        return baseUrl() + "&do=jar&siteKey=" +
            URLEncoder.encode(siteKey, StandardCharsets.UTF_8);
    }

    public interface Diagnostic {
        void httpResponse(int status, boolean range);
        default void failure(String code) {}
    }
    private volatile Diagnostic diagnostic;
    private final java.util.concurrent.atomic.AtomicInteger diagnosticBudget = new java.util.concurrent.atomic.AtomicInteger(16);
    public void setDiagnostic(Diagnostic diagnostic) { this.diagnostic = diagnostic; }

    @Override public Response serve(IHTTPSession session) {
        Response response = dispatch(session);
        Diagnostic sink = diagnostic;
        if (sink != null && diagnosticBudget.getAndDecrement() > 0) {
            // The callback cannot access request URLs, headers, cookies or plugin exceptions.
            try { sink.httpResponse(response.getStatus().getRequestStatus(), session.getHeaders().containsKey("range")); }
            catch (RuntimeException ignored) { /* Diagnostics cannot alter the media response. */ }
        }
        return response;
    }

    private void diagnosticFailure(String code) {
        Diagnostic sink = diagnostic;
        if (sink != null && diagnosticBudget.getAndDecrement() > 0) {
            try { sink.failure(code); }
            catch (Throwable ignored) { /* Diagnostics never alter a response. */ }
        }
    }

    private Response dispatch(IHTTPSession session) {
        if (session.getMethod() != Method.GET && session.getMethod() != Method.HEAD) {
            return error(Response.Status.METHOD_NOT_ALLOWED);
        }
        String path = session.getUri();
        if (!"/proxy".equals(path) && !"/health".equals(path)) {
            return error(Response.Status.NOT_FOUND);
        }
        Map<String, String> params = new LinkedHashMap<>(session.getParms());
        String supplied = params.remove("session");
        if (!validToken(supplied)) return error(Response.Status.FORBIDDEN);
        String selectedSite = params.get("siteKey");
        if (selectedSite != null && !siteKey.equals(selectedSite)) {
            return error(Response.Status.FORBIDDEN);
        }
        if ("/health".equals(path)) {
            if (!siteKey.equals(selectedSite)) return error(Response.Status.FORBIDDEN);
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "");
        }
        SpiderCallback callback = selectedSite == null ? staticJarProxy : siteProxy;
        if (callback == null) return error(Response.Status.FORBIDDEN);
        // FongMi forwards HTTP headers as Spider arguments, but host auth
        // cannot be overridden by a forged header.
        for (Map.Entry<String, String> header : session.getHeaders().entrySet()) {
            if (!header.getKey().equalsIgnoreCase("session") &&
                !header.getKey().equalsIgnoreCase("siteKey")) {
                params.put(header.getKey(), header.getValue());
            }
        }
        try {
            return fromUpstreamResponse(rewriteHls(callback.proxy(params)));
        } catch (Throwable failure) {
            // Never disclose plugin URLs, credentials or stack traces on wire.
            diagnosticFailure("PROXY_CALLBACK_OR_REWRITE_FAILED");
            return error(BAD_GATEWAY);
        }
    }

    private boolean validToken(String supplied) {
        return supplied != null && MessageDigest.isEqual(
            token.getBytes(StandardCharsets.US_ASCII),
            supplied.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * FongMi's fixed-port aliases inside an HLS manifest must use the current
     * private Spider session. Inspect only status-200 playlist bytes and leave
     * binary MP4/TS, partial responses and compressed streams untouched.
     */
    private Object[] rewriteHls(Object[] response) throws IOException {
        if (response == null || response.length < 3 ||
            !(response[0] instanceof Integer) || ((Integer) response[0]) != 200 ||
            !(response[1] instanceof String) || !(response[2] instanceof InputStream)) {
            return response;
        }
        if (response.length > 3 && response[3] instanceof Map) {
            for (Object header : ((Map<?, ?>) response[3]).keySet()) {
                if ("content-encoding".equalsIgnoreCase(String.valueOf(header))) {
                    return response;
                }
            }
        }
        String mime = ((String) response[1]).toLowerCase(java.util.Locale.ROOT);
        boolean declared = mime.contains("mpegurl") || mime.contains("m3u8");
        // A UTF-8 BOM precedes #EXTM3U in some generic-MIME Spider streams.
        // Peek at most 10 bytes, then unread all bytes for binary responses.
        PushbackInputStream probe = new PushbackInputStream((InputStream) response[2], 10);
        byte[] signature = "#EXTM3U".getBytes(StandardCharsets.US_ASCII);
        byte[] first = new byte[10];
        int count = 0;
        while (count < first.length) {
            int bytes;
            try { bytes = probe.read(first, count, first.length - count); }
            catch (IOException | RuntimeException | Error failure) {
                try { probe.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                diagnosticFailure("HLS_PREFIX_READ_FAILED");
                throw failure;
            }
            if (bytes <= 0) break;
            count += bytes;
        }
        if (count > 0) probe.unread(first, 0, count);
        int offset = count >= 3 && first[0] == (byte) 0xEF &&
            first[1] == (byte) 0xBB && first[2] == (byte) 0xBF ? 3 : 0;
        boolean identified = count - offset >= signature.length;
        for (int i = 0; i < signature.length && identified; i++) {
            if (first[offset + i] != signature[i]) identified = false;
        }
        if (!declared && !identified) {
            Object[] copy = response.clone();
            copy[2] = probe;
            return copy;
        }
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        try {
            int size;
            while ((size = probe.read(chunk)) != -1) {
                if (content.size() + size > NativeFongMiHls.MAX_MANIFEST_BYTES) {
                    diagnosticFailure("HLS_MANIFEST_SIZE_LIMIT");
                    throw new IOException("HLS manifest exceeded safe size");
                }
                content.write(chunk, 0, size);
            }
        } finally {
            probe.close();
        }
        String playlist = new String(content.toByteArray(), StandardCharsets.UTF_8);
        String rewritten = NativeFongMiHls.rewrite(playlist, siteKey, this);
        if (rewritten == null) {
            diagnosticFailure("HLS_MANIFEST_REJECTED");
            diagnosticFailure(NativeFongMiHls.rejectionCode(playlist));
            throw new IOException("Invalid approved HLS playlist");
        }
        Object[] copy = response.clone();
        copy[1] = "application/vnd.apple.mpegurl";
        copy[2] = new ByteArrayInputStream(rewritten.getBytes(StandardCharsets.UTF_8));
        return copy;
    }

    /** FongMi Proxy.createResponse(Object[]) with a private-host error body. */
    public static Response fromUpstreamResponse(Object[] values) {
        if (values == null || values.length == 0) {
            return error(BAD_GATEWAY);
        }
        if (values[0] instanceof Response) return mark((Response) values[0]);
        if (values.length < 3 || !(values[0] instanceof Integer) ||
            !(values[1] instanceof String) || !(values[2] instanceof InputStream)) {
            return error(BAD_GATEWAY);
        }
        int code = (Integer) values[0];
        String mime = (String) values[1];
        InputStream input = (InputStream) values[2];
        Response.Status known = Response.Status.lookup(code);
        Response.IStatus status = known != null ? known :
            code >= 100 && code <= 599 ? new CustomStatus(code) :
            Response.Status.INTERNAL_ERROR;
        try {
            Response result = NanoHTTPD.newChunkedResponse(status, mime, input);
            if (values.length > 3 && values[3] instanceof Map) {
                Map<?, ?> headers = (Map<?, ?>) values[3];
                for (Map.Entry<?, ?> header : headers.entrySet()) {
                    if (!(header.getKey() instanceof String) ||
                        !(header.getValue() instanceof String)) continue;
                    String name = (String) header.getKey();
                    String value = (String) header.getValue();
                    if (name.equalsIgnoreCase("content-length") ||
                        name.equalsIgnoreCase("transfer-encoding") ||
                        name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0 ||
                        value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) continue;
                    result.addHeader(name, value);
                }
            }
            return mark(result);
        } catch (RuntimeException | Error failure) {
            try { input.close(); } catch (IOException closeError) {
                failure.addSuppressed(closeError);
            }
            throw failure;
        }
    }

    private static final Response.IStatus BAD_GATEWAY = new CustomStatus(502);

    private static Response mark(Response response) {
        if (response.getStatus().getRequestStatus() >= 400) {
            response.addHeader("X-TV-Proxy-Error", "1");
        }
        return response;
    }
    private static Response error(Response.IStatus status) {
        return mark(NanoHTTPD.newFixedLengthResponse(
            status, "text/plain", "Proxy unavailable"));
    }
    private static final class CustomStatus implements Response.IStatus {
        private final int code;
        private CustomStatus(int code) { this.code = code; }
        @Override public int getRequestStatus() { return code; }
        @Override public String getDescription() { return code + " Proxy Status"; }
    }
    @Override public void close() { diagnostic = null; stop(); }
}
