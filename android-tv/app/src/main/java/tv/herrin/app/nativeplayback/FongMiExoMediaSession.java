/* SPDX-License-Identifier: GPL-3.0-only
 * FongMi/TV 305dff233cde512e2c83f71529b7c3ecfd61fefc. Original author: FongMi.
 * HERRIN 2026-11-05: compatibility API delegates to the sole production
 * HerrinPlaybackAdapter/PlayerManager. No independent Exo builder or data stack.
 */
package tv.herrin.app.nativeplayback;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import tv.herrin.app.HerrinPlaybackAdapter;

@UnstableApi
public final class FongMiExoMediaSession implements AutoCloseable {
    private final Context context;
    private final Map<String, String> headers;
    private HerrinPlaybackAdapter adapter;
    private boolean released;

    private FongMiExoMediaSession(Context context, Map<String, String> headers) {
        this.context = context.getApplicationContext();
        MediaItemFactory.validateHeaders(headers);
        this.headers = Collections.unmodifiableMap(new HashMap<>(headers));
    }
    @NonNull public static FongMiExoMediaSession create(
            @NonNull Context context, @NonNull Map<String, String> headers) {
        return new FongMiExoMediaSession(context, headers);
    }
    @NonNull public ExoPlayer player() {
        ensureActive();
        if (adapter == null) throw new IllegalStateException("Media not prepared");
        return adapter.getPlayer();
    }
    public void prepare(@NonNull String mediaId, @NonNull String url, @Nullable String mime) {
        ensureActive();
        if (mediaId.isBlank()) throw new IllegalArgumentException("Expected nonempty media ID");
        if (adapter == null) adapter = HerrinPlaybackAdapter.createRemote(context, mediaId, url, headers, mime);
        adapter.prepareRemote(mediaId, url, mime);
    }
    public void setVideoSurface(@Nullable android.view.Surface surface) {
        if (surface == null) player().clearVideoSurface();
        else player().setVideoSurface(surface);
    }
    public void play() { ensurePrepared(); adapter.play(); }
    public void pause() { ensurePrepared(); adapter.pause(); }
    public void seekTo(long positionMs) {
        ensurePrepared();
        if (positionMs < 0) throw new IllegalArgumentException("Negative seek position");
        adapter.seekTo(positionMs);
    }
    private void ensureActive() {
        if (released) throw new IllegalStateException("Media session released");
    }
    private void ensurePrepared() { player(); }
    @Override public void close() {
        if (released) return;
        released = true;
        if (adapter != null) adapter.close();
        adapter = null;
    }
}
