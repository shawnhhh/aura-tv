/* SPDX-License-Identifier: GPL-3.0-only
 * HERRIN HTTP-content verification at FongMi ParseWebView's Sniffer boundary.
 * Unlike URL suffixes, MIME and bounded signature bytes are media evidence.
 */
package tv.herrin.app.fongmi.parse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
public final class MediaSniffer {
    public static boolean isMedia(String contentType, byte[] prefix) {
        String mime = contentType.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        if (mime.startsWith("video/") || mime.startsWith("audio/") || mime.equals("application/vnd.apple.mpegurl") ||
            mime.equals("application/x-mpegurl") || mime.equals("application/dash+xml")) return true;
        if (mime.startsWith("text/html") || mime.startsWith("image/") || mime.contains("javascript")) return false;
        String text = new String(prefix, StandardCharsets.UTF_8).replaceFirst("^[\\uFEFF\\s]+", "");
        if (text.startsWith("#EXTM3U") || text.contains("<MPD")) return true;
        if (prefix.length >= 8 && new String(prefix,4,4,StandardCharsets.US_ASCII).equals("ftyp")) return true;
        return prefix.length >= 4 && (prefix[0] & 255) == 0x1a && (prefix[1] & 255) == 0x45 &&
            (prefix[2] & 255) == 0xdf && (prefix[3] & 255) == 0xa3;
    }
}
