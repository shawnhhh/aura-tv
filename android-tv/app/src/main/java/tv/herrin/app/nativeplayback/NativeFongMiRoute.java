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
        // A URL aimed at our CURRENT port must carry the real session, never
        // a forged or stale one. Legacy aliases cannot assert a session.
        if (currentPort && !proxy.sessionToken().equals(priorToken)) return null;
        if (legacy && priorToken != null) return null;
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
