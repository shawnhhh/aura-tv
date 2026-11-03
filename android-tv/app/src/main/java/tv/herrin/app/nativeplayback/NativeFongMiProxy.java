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
    public String baseUrl() {
        return "http://127.0.0.1:" + port() + "/proxy?session=" + token;
    }
    public String siteUrl() {
        return baseUrl() + "&do=jar&siteKey=" +
            URLEncoder.encode(siteKey, StandardCharsets.UTF_8);
    }

    @Override public Response serve(IHTTPSession session) {
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
        String selectedSite = params.remove("siteKey");
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
            return fromUpstreamResponse(callback.proxy(params));
        } catch (Throwable failure) {
            // Never disclose plugin URLs, credentials or stack traces on wire.
            return error(Response.Status.BAD_GATEWAY);
        }
    }

    private boolean validToken(String supplied) {
        return supplied != null && MessageDigest.isEqual(
            token.getBytes(StandardCharsets.US_ASCII),
            supplied.getBytes(StandardCharsets.US_ASCII));
    }

    /** FongMi Proxy.createResponse(Object[]) with a private-host error body. */
    public static Response fromUpstreamResponse(Object[] values) {
        if (values == null || values.length == 0) {
            return error(Response.Status.BAD_GATEWAY);
        }
        if (values[0] instanceof Response) return mark((Response) values[0]);
        if (values.length < 3 || !(values[0] instanceof Integer) ||
            !(values[1] instanceof String) || !(values[2] instanceof InputStream)) {
            return error(Response.Status.BAD_GATEWAY);
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

    private static Response mark(Response response) {
        if (response.getStatus().getRequestStatus() >= 400) {
            response.addHeader("X-TV-Proxy-Error", "1");
        }
        return response;
    }
    private static Response error(Response.Status status) {
        return mark(NanoHTTPD.newFixedLengthResponse(
            status, "text/plain", "Proxy unavailable"));
    }
    private static final class CustomStatus implements Response.IStatus {
        private final int code;
        private CustomStatus(int code) { this.code = code; }
        @Override public int getRequestStatus() { return code; }
        @Override public String getDescription() { return code + " Proxy Status"; }
    }
    @Override public void close() { stop(); }
}
