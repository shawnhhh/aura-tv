/* SPDX-License-Identifier: GPL-3.0-only
 * FongMi host adaptation: rebind HLS nested references from upstream fixed
 * ports to the selected Spider's private NanoHTTPD session.
 */
package tv.herrin.app.nativeplayback;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Real HLS playlist URI conversion; never touches MP4/TS bytes. */
public final class NativeFongMiHls {
    public static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final Pattern URI_ATTRIBUTE =
        Pattern.compile("URI=\"([^\"\\r\\n]{1,4096})\"");

    private NativeFongMiHls() {}

    /** Fixed codes only; never export playlist text or private nested references. */
    public static String rejectionCode(String playlist) {
        if (playlist == null || playlist.isEmpty()) return "HLS_BODY_EMPTY";
        if (playlist.length() > MAX_MANIFEST_BYTES) return "HLS_MANIFEST_SIZE_LIMIT";
        String header = playlist.startsWith("\uFEFF") ? playlist.substring(1) : playlist;
        if (header.length() >= 2 && header.charAt(0) == 0x1F && header.charAt(1) == 0xFFFD)
            return "HLS_BODY_GZIP";
        header = header.stripLeading();
        if (header.startsWith("#EXTM3U")) return "HLS_REFERENCE_REWRITE_REJECTED";
        if (header.startsWith("{") || header.startsWith("[")) return "HLS_BODY_JSON";
        if (header.startsWith("http://") || header.startsWith("https://")) return "HLS_BODY_URL";
        if (header.startsWith("<?xml") || header.startsWith("<Error")) return "HLS_BODY_XML";
        if (header.startsWith("\uFFFD\uFFFD") || header.indexOf('\u0000') >= 0) return "HLS_BODY_BINARY_OR_UTF16";
        if (header.startsWith("I0VYVE0zV")) return "HLS_BODY_BASE64";
        if (header.regionMatches(true, 0, "forbidden", 0, 9) ||
            header.regionMatches(true, 0, "access denied", 0, 13)) return "HLS_BODY_ACCESS_DENIED";
        if (header.regionMatches(true, 0, "error", 0, 5)) return "HLS_BODY_ERROR_TEXT";
        if (header.regionMatches(true, 0, "<html", 0, 5) ||
            header.regionMatches(true, 0, "<!doctype", 0, 9)) return "HLS_BODY_HTML";
        return "HLS_HEADER_INVALID";
    }

    /** A callback may carry one media reference, never a multiline URL list. */
    public static String singleReference(String body) {
        if (body == null) return null;
        String value = body.trim();
        if (value.length() < 1 || value.length() > 8192) return null;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) <= ' ' || value.charAt(i) == '\\') return null;
        try {
            URI uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) ||
                uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return null;
            return value;
        } catch (Exception ignored) { return null; }
    }

    /** Only a fetched same-origin manifest has a trustworthy remote relative base. */
    public static String resolveReferences(String playlist, String source) {
        if (playlist == null || playlist.length() > MAX_MANIFEST_BYTES) return null;
        String body = playlist.startsWith("\uFEFF") ? playlist.substring(1) : playlist;
        body = body.stripLeading();
        if (!body.startsWith("#EXTM3U")) return null;
        final URI base;
        try { base = new URI(source); } catch (Exception ignored) { return null; }
        StringBuilder output = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            String plain = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (plain.startsWith("#")) {
                Matcher finder = URI_ATTRIBUTE.matcher(plain);
                StringBuffer changed = new StringBuffer();
                while (finder.find()) {
                    String resolved = sameOriginReference(base, finder.group(1));
                    if (resolved == null) return null;
                    finder.appendReplacement(changed, Matcher.quoteReplacement("URI=\"" + resolved + "\""));
                }
                finder.appendTail(changed);
                plain = changed.toString();
            } else if (!plain.isBlank()) {
                plain = sameOriginReference(base, plain);
                if (plain == null) return null;
            }
            if (output.length() > 0) output.append('\n');
            output.append(plain);
        }
        return output.toString();
    }

    private static String sameOriginReference(URI base, String ref) {
        try {
            String candidate = singleReference(base.resolve(ref.trim()).toString());
            if (candidate == null) return null;
            URI uri = new URI(candidate);
            if (!base.getScheme().equalsIgnoreCase(uri.getScheme()) ||
                !base.getHost().equalsIgnoreCase(uri.getHost()) || effectivePort(base) != effectivePort(uri)) return null;
            return candidate;
        } catch (Exception ignored) { return null; }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    public static String rewrite(String playlist, String siteKey, NativeFongMiProxy proxy) {
        if (playlist == null || playlist.length() > MAX_MANIFEST_BYTES) return null;
        String header = playlist.startsWith("\uFEFF") ? playlist.substring(1) : playlist;
        if (!header.stripLeading().startsWith("#EXTM3U")) return null;
        // Emit a canonical UTF-8 HLS header. A decoded U+FEFF BOM can make
        // Media3's HlsPlaylistParser reject otherwise valid Spider playlists.
        String[] lines = header.split("\n", -1);
        StringBuilder result = new StringBuilder(header.length() + 128);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String plain = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            String changed;
            if (plain.startsWith("#")) {
                Matcher finder = URI_ATTRIBUTE.matcher(plain);
                StringBuffer output = new StringBuffer();
                while (finder.find()) {
                    String bound = translate(finder.group(1), siteKey, proxy);
                    if (bound == null) return null;
                    finder.appendReplacement(output,
                        Matcher.quoteReplacement("URI=" + (char) 34 + bound + (char) 34));
                }
                finder.appendTail(output);
                changed = output.toString();
            } else if (plain.isBlank()) {
                changed = plain;
            } else {
                changed = translate(plain, siteKey, proxy);
                if (changed == null) return null;
            }
            result.append(changed);
            if (line.endsWith("\r")) result.append('\r');
            if (i < lines.length - 1) result.append('\n');
        }
        return result.toString();
    }

    private static String translate(String original, String siteKey, NativeFongMiProxy proxy) {
        String candidate = original.trim();
        if (candidate.regionMatches(true, 0, "proxy://", 0, 8)) {
            return NativeFongMiRoute.url(candidate, siteKey, proxy);
        }
        try {
            URI uri = new URI(candidate);
            String host = uri.getHost();
            boolean local = "http".equalsIgnoreCase(uri.getScheme()) &&
                host != null && (host.equalsIgnoreCase("localhost") ||
                host.equals("127.0.0.1") || host.equals("::1"));
            if (local) return NativeFongMiRoute.url(candidate, siteKey, proxy);
        } catch (Exception ignored) {
            // Relative paths and external non-local references are unchanged.
        }
        return original;
    }
}
