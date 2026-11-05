/* SPDX-License-Identifier: GPL-3.0-only
 * FongMi/TV proxy URL conversion in the authorized Herrin Android host.
 * No do= operation whitelist: selected Spider determines its own dispatch.
 */
package tv.herrin.app.nativeplayback;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Routes original FongMi proxy:// aliases and site-bound /proxy URLs onto the
 * current authorized site's native FongMi HTTP proxy, without a second proxy.
 */
public final class NativeFongMiRoute {
    private static final int MAX_URL = 8192;
    private NativeFongMiRoute() {}

    /** An authenticated native loopback route, independent of do operation. */
    public static boolean isNativeProxy(String mediaUrl) {
        try {
            URI uri = new URI(mediaUrl);
            if (!isLoopback(uri) || !"/proxy".equals(uri.getRawPath())) return false;
            Map<String, String> params = parse(uri.getRawQuery());
            return params != null && params.containsKey("session");
        } catch (Exception ignored) {
            return false;
        }
    }

    public enum Health {
        READY, NOT_PROXY, INVALID_SESSION, INVALID_PORT,
        HTTP_403, HTTP_404, HTTP_5XX, HTTP_OTHER,
        CONNECT_REFUSED, TIMEOUT, IO_MAIN_THREAD, IO_CONNECT, IO_SOCKET,
        IO_POLICY, IO_TLS, IO_STREAM, IO_UNKNOWN, IO_FAILURE
    }

    /** A bounded real HTTP probe of this exact active loopback session. */
    public static Health probe(String mediaUrl, String authorizedSite) {
        URI uri;
        try { uri = new URI(mediaUrl); }
        catch (Exception invalid) { return Health.NOT_PROXY; }
        if (!isLoopback(uri) || !"/proxy".equals(uri.getPath())) return Health.NOT_PROXY;
        if (uri.getPort() < 1 || uri.getPort() > 65535) return Health.INVALID_PORT;
        Map<String, String> args = parse(uri.getRawQuery());
        if (args == null || args.get("session") == null ||
            args.get("session").length() < 24 ||
            authorizedSite == null || authorizedSite.isBlank()) return Health.INVALID_SESSION;
        if (args.containsKey("siteKey") &&
            !authorizedSite.equals(args.get("siteKey"))) return Health.HTTP_403;
        String healthUrl = "http://127.0.0.1:" + uri.getPort() +
            "/health?session=" + URLEncoder.encode(args.get("session"), StandardCharsets.UTF_8) +
            "&siteKey=" + URLEncoder.encode(authorizedSite, StandardCharsets.UTF_8);
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new URI(healthUrl).toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status == 204) return Health.READY;
            if (status == 403) return Health.HTTP_403;
            if (status == 404) return Health.HTTP_404;
            if (status >= 500 && status <= 599) return Health.HTTP_5XX;
            return Health.HTTP_OTHER;
        } catch (Exception failure) {
            return healthFailure(failure);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Fixed codes only: never expose exception messages, private URLs or credentials. */
    public static Health healthFailure(Throwable failure) {
        java.util.List<Throwable> causes = new java.util.ArrayList<>();
        for (Throwable next = failure; next != null && causes.size() < 12; next = next.getCause()) causes.add(next);
        for (Throwable next : causes) {
            if (next.getClass().getName().equals("android.os.NetworkOnMainThreadException")) return Health.IO_MAIN_THREAD;
            if (next instanceof SecurityException) return Health.IO_POLICY;
            if (next instanceof javax.net.ssl.SSLException || next instanceof java.security.cert.CertificateException) return Health.IO_TLS;
            // Android cleartext denial is UnknownServiceException. Inspect only
            // its fixed platform marker; never retain or return the message.
            if (next instanceof java.net.UnknownServiceException &&
                String.valueOf(next.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("cleartext")) return Health.IO_POLICY;
        }
        for (Throwable next : causes) {
            if (next instanceof java.net.SocketTimeoutException) return Health.TIMEOUT;
            if (next instanceof java.net.ConnectException) return Health.CONNECT_REFUSED;
            if (next instanceof java.net.UnknownHostException || next instanceof java.net.NoRouteToHostException) return Health.IO_CONNECT;
            if (next instanceof java.net.SocketException) return Health.IO_SOCKET;
        }
        for (Throwable next : causes) if (next instanceof java.io.IOException) return Health.IO_STREAM;
        return Health.IO_UNKNOWN;
    }

    /** Returns null for an invalid or foreign proxy. Ordinary external media
     * URLs are unchanged and validated by the existing media URL boundary. */
    public static String url(String source, String siteKey, NativeFongMiProxy proxy) {
        if (source == null || source.isBlank() || source.length() > MAX_URL ||
            siteKey == null || siteKey.isBlank() || proxy == null) return null;
        final boolean legacy = source.regionMatches(true, 0, "proxy://", 0, 8);
        final URI uri;
        final String query;
        if (legacy) {
            String tail = source.substring(8);
            if (tail.startsWith("?")) tail = tail.substring(1);
            if (tail.startsWith("/") || tail.startsWith("#") || tail.contains("#")) return null;
            query = tail;
            uri = null;
        } else {
            try { uri = new URI(source); } catch (Exception ignored) { return null; }
            if (!isLoopback(uri)) return source;
            if (!"/proxy".equals(uri.getRawPath())) return null;
            query = uri.getRawQuery();
        }
        Map<String, String> params = parse(query);
        if (params == null || params.size() > 32) return null;
        String routeSite = params.get("siteKey");
        if (routeSite != null && !siteKey.equals(routeSite)) return null;
        boolean currentPort = !legacy && uri.getPort() == proxy.port();
        String priorToken = params.remove("session");
        // Current URLs require their real session. The host may also carry
        // static capabilities it issued to this same approved JAR/grant; Init
        // can cache one before the active site (and its listener) changes.
        boolean inheritedStatic = !legacy && routeSite == null &&
            proxy.ownsRetiredStaticOrigin(uri.getPort(), priorToken);
        if (currentPort && !proxy.sessionToken().equals(priorToken) && !inheritedStatic) return null;
        // Never exchange an unknown local server's token for our capability.
        // Site-bound URLs remain subject to the full site check above.
        if (!currentPort && priorToken != null && !inheritedStatic) return null;
        // Site-free FongMi routes are the already authorized JAR's static
        // Proxy.proxy callback. They never execute arbitrary scripts.
        if (routeSite == null && !proxy.hasStaticHandler()) return null;
        if (params.containsKey("__herrin_static")) return null;
        StringBuilder answer = new StringBuilder(proxy.baseUrl());
        for (Map.Entry<String, String> entry : params.entrySet()) {
            answer.append('&')
                .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return answer.toString();
    }

    public static String playerContent(String json, String siteKey, NativeFongMiProxy proxy) {
        try {
            JSONObject root = new JSONObject(json);
            Object data = root.opt("url");
            if (data instanceof String) {
                String next = url((String) data, siteKey, proxy);
                if (next == null) return null;
                root.put("url", next);
            } else if (data instanceof JSONArray) {
                JSONArray all = (JSONArray) data;
                for (int i = 0; i < all.length(); i++) {
                    Object entry = all.opt(i);
                    if (!(entry instanceof String)) continue;
                    String next = url((String) entry, siteKey, proxy);
                    if (next == null) return null;
                    all.put(i, next);
                }
            } else if (data instanceof JSONObject) {
                JSONArray values = ((JSONObject) data).optJSONArray("values");
                if (values != null) {
                    for (int i = 0; i < values.length(); i++) {
                        JSONObject row = values.optJSONObject(i);
                        if (row == null || !row.has("v")) continue;
                        String next = url(row.optString("v"), siteKey, proxy);
                        if (next == null) return null;
                        row.put("v", next);
                    }
                }
            }
            return root.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isLoopback(URI uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        return host != null && (host.equalsIgnoreCase("localhost") ||
            host.equals("127.0.0.1") || host.equals("::1"));
    }

    private static Map<String, String> parse(String query) {
        if (query == null || query.length() > MAX_URL) return null;
        Map<String, String> out = new LinkedHashMap<>();
        if (query.isEmpty()) return out;
        try {
            for (String pair : query.split("&", -1)) {
                if (pair.isEmpty()) return null;
                String key = URLDecoder.decode(pair.substring(0,
                    pair.indexOf('=') < 0 ? pair.length() : pair.indexOf('=')),
                    StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.contains("=") ?
                    pair.substring(pair.indexOf('=') + 1) : "", StandardCharsets.UTF_8);
                if (key.isEmpty() || out.containsKey(key)) return null;
                out.put(key, val);
            }
            return out;
        } catch (IllegalArgumentException invalidPercentEncoding) {
            return null;
        }
    }
}
