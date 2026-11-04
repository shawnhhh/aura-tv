package tv.herrin.app.nativeplayback;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Android Media3 host bridge for FongMi playback results.
 *
 * The pinned FongMi/TV PlayerManager and MediaItemFactory depend on a custom
 * Media3 fork (DiscMenuController, IsoUri, setAdblock, etc.) and cannot be
 * source-compiled against Herrin's stock Media3 1.9.4. This host bridge
 * supplies real ExoPlayer media creation without pretending to be that fork.
 * It must be called only after the authorized loader/parse/proxy stage.
 *
 * FongMi/TV fongmi@305dff233cde512e2c83f71529b7c3ecfd61fefc,
 * GPL-3.0; this integration code is part of Herrin's GPL-3.0 playback work.
 */
public final class FongMiExoMediaSession implements AutoCloseable {
    private final ExoPlayer player;
    private boolean released;

    private FongMiExoMediaSession(ExoPlayer player) {
        this.player = player;
    }

    /** Build a real ExoPlayer with HTTP headers applied to media segment requests. */
    @NonNull
    public static FongMiExoMediaSession create(
            @NonNull Context context, @NonNull Map<String, String> headers) {
        Map<String, String> safeHeaders = new HashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey() == null || header.getValue() == null ||
                    header.getKey().contains("\r") || header.getKey().contains("\n") ||
                    header.getValue().contains("\r") || header.getValue().contains("\n")) {
                throw new IllegalArgumentException("Invalid media request header");
            }
            safeHeaders.put(header.getKey(), header.getValue());
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(Collections.unmodifiableMap(safeHeaders));
        DefaultDataSource.Factory data = new DefaultDataSource.Factory(
                context.getApplicationContext(), http);
        ExoPlayer player = new ExoPlayer.Builder(context.getApplicationContext())
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data))
                .build();
        return new FongMiExoMediaSession(player);
    }

    @NonNull
    public ExoPlayer player() {
        ensureActive();
        return player;
    }

    /**
     * Prepare actual media bytes for ExoPlayer. No URL probing or parser
     * reimplementation occurs here: source authorization happens upstream.
     */
    public void prepare(@NonNull String mediaId, @NonNull String url,
                        @Nullable String mimeType) {
        ensureActive();
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (mediaId.isBlank() || uri.getHost() == null ||
                !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Expected authorized HTTP(S) media URI");
        }
        MediaItem.Builder item = new MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(uri);
        if (mimeType != null && !mimeType.isBlank()) item.setMimeType(mimeType);
        player.setMediaItem(item.build());
        player.prepare();
    }

    private void ensureActive() {
        if (released) throw new IllegalStateException("Media session released");
    }

    @Override public void close() {
        if (released) return;
        released = true;
        player.release();
    }
}
