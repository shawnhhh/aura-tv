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
