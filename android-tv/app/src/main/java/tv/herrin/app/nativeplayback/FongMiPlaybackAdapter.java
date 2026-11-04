package tv.herrin.app.nativeplayback;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONObject;

/**
 * Thin production boundary between an authorized FongMi playerContent response
 * and Herrin's real Media3 host. No Spider execution or duplicate HTTP proxy.
 * FongMi/TV fongmi@305dff233cde512e2c83f71529b7c3ecfd61fefc (GPL-3.0).
 *
 * ParseJob must already have resolved jx/playUrl before this method is called.
 */
@UnstableApi
public final class FongMiPlaybackAdapter {
    private FongMiPlaybackAdapter() {}

    /**
     * Normalize only the authorized FongMi proxy alias, then hand actual media
     * bytes to Exo. A parser instruction is not a playable URL.
     */
    @NonNull
    public static FongMiExoMediaSession prepareResolved(
            @NonNull Context context, @NonNull String mediaId,
            @NonNull String playerContent, @NonNull String authorizedSite,
            @NonNull NativeFongMiProxy proxy) throws Exception {
        JSONObject raw = new JSONObject(playerContent);
        if (raw.optInt("jx", 0) != 0 || raw.optInt("parse", 0) != 0 ||
                !raw.optString("playUrl", "").isBlank()) {
            throw new IllegalArgumentException("FongMi ParseJob has not resolved playerContent");
        }
        String normalized = NativeFongMiRoute.playerContent(
                playerContent, authorizedSite, proxy);
        if (normalized == null) throw new IllegalArgumentException("Invalid authorized proxy route");
        JSONObject parsed = new JSONObject(normalized);
        Object urlValue = parsed.opt("url");
        if (!(urlValue instanceof String) || ((String) urlValue).isBlank())
            throw new IllegalArgumentException("Expected one resolved media URL");
        String url = (String) urlValue;
        Map<String, String> headers = new HashMap<>();
        JSONObject jsonHeaders = parsed.optJSONObject("header");
        if (jsonHeaders != null) {
            Iterator<String> keys = jsonHeaders.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = jsonHeaders.opt(key);
                if (!(value instanceof String))
                    throw new IllegalArgumentException("Invalid media header value");
                headers.put(key, (String) value);
            }
        }
        FongMiExoMediaSession session = FongMiExoMediaSession.create(
                context, Collections.unmodifiableMap(headers));
        try {
            String mime = parsed.optString("format", "");
            session.prepare(mediaId, url, mime.isBlank() ? null : mime);
            return session;
        } catch (Exception failure) {
            session.close();
            throw failure;
        }
    }
}
