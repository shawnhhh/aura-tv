/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.kernel.android;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.danmaku.DanmakuConfig;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.server.Server;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import tv.herrin.kernel.Kernel;

/** Direct calls into whole upstream modules. This class does not resolve media. */
public final class FongMiKernel implements Kernel {
    private static final AtomicReference<FongMiKernel> OWNER = new AtomicReference<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, MovieEntry> movies = new HashMap<>();
    private final Map<String, Selection> episodes = new HashMap<>();
    private final List<CompletableFuture<?>> pending = new ArrayList<>();
    private Consumer<State> observer = state -> {};
    private WarehouseAccess access;
    private PlayerManager manager;
    private Player observedPlayer;
    private PlayerView view;
    private Selection active;
    private volatile long epoch;
    private long request;
    private volatile boolean closed;
    private boolean firstFrame;
    private boolean loaded;
    private Phase phase = Phase.IDLE;

    private record MovieEntry(String siteKey, Vod vod) {}
    private record Selection(String siteKey, Vod vod, Flag flag, com.fongmi.android.tv.bean.Episode episode) {}

    public FongMiKernel() {
        requireMain();
        if (!(App.get() instanceof KernelApplication))
            throw new IllegalStateException("Install KernelApplication before constructing the kernel");
        if (!OWNER.compareAndSet(null, this))
            throw new IllegalStateException("A FongMi kernel already owns this process");
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Kernel lifecycle must run on the Android main thread");
    }

    private void requireOpen() {
        requireMain();
        if (closed) throw new IllegalStateException("Kernel is closed");
    }

    private com.fongmi.android.tv.bean.Site site(String key) {
        if (!loaded || access == null || !access.approvedSiteKeys().contains(key))
            throw new SecurityException("Site is not authorized in the loaded warehouse");
        var site = VodConfig.get().getSite(key);
        if (site.isEmpty()) throw new IllegalArgumentException("Site is absent from warehouse");
        return site;
    }

    @Override public CompletionStage<List<Site>> load(WarehouseAccess next) {
        requireOpen();
        long ticket = ++epoch;
        ++request;
        loaded = false;
        access = next;
        movies.clear();
        episodes.clear();
        releasePlayer();
        var future = track(new CompletableFuture<List<Site>>());
        emit(Phase.CONFIGURING, "");
        // Original Config persistence and VodConfig loading, including depot,
        // Spider initialization, headers, global flags and parser configuration.
        io.execute(() -> {
            try {
                Config config = Config.find(next.warehouse().toString(), 0);
                main.post(() -> {
                    if (!valid(ticket, future)) return;
                    VodConfig.load(config, new Callback() {
                        @Override public void success() {
                            if (!valid(ticket, future)) return;
                            loaded = true;
                            List<Site> sites = new ArrayList<>();
                            for (String key : next.approvedSiteKeys()) {
                                var original = VodConfig.get().getSite(key);
                                if (!original.isEmpty()) sites.add(new Site(key, original.getName()));
                            }
                            emit(Phase.READY, "");
                            future.complete(List.copyOf(sites));
                        }
                        @Override public void error(String message) { fail(ticket, future, "CONFIG_LOAD_FAILED"); }
                    });
                });
            } catch (Exception failure) { main.post(() -> fail(ticket, future, "CONFIG_LOAD_FAILED")); }
        });
        return future;
    }

    @Override public CompletionStage<List<Movie>> search(String siteKey, String query) {
        requireOpen();
        var original = site(siteKey);
        long ticket = epoch;
        return background(ticket, () -> SiteApi.searchContent(original, query, false, "1"), result -> {
            List<Movie> found = new ArrayList<>();
            for (Vod vod : result.getList()) found.add(remember(siteKey, vod));
            return List.copyOf(found);
        });
    }

    @Override public CompletionStage<Detail> details(String handle) {
        requireOpen();
        MovieEntry entry = movies.get(handle);
        if (entry == null) throw new SecurityException("Unknown or retired movie handle");
        site(entry.siteKey());
        return background(epoch, () -> SiteApi.detailContent(entry.siteKey(), entry.vod().getId()), result -> {
            if (result.getList().isEmpty()) throw new IllegalStateException("EMPTY_DETAIL");
            Vod vod = result.getVod();
            List<Line> lines = new ArrayList<>();
            // SiteApi already invokes original Vod.setFlags/Source.parse. No
            // delimiter splitting or episode URL decoding is done in the host.
            for (Flag flag : vod.getFlags()) {
                List<Episode> items = new ArrayList<>();
                for (var episode : flag.getEpisodes()) {
                    String id = UUID.randomUUID().toString();
                    episodes.put(id, new Selection(entry.siteKey(), vod, flag, episode));
                    items.add(new Episode(id, episode.getName(), items.size()));
                }
                lines.add(new Line(flag.getShow(), items));
            }
            return new Detail(remember(entry.siteKey(), vod), lines);
        });
    }

    private Movie remember(String siteKey, Vod vod) {
        String handle = UUID.randomUUID().toString();
        movies.put(handle, new MovieEntry(siteKey, vod));
        return new Movie(handle, siteKey, vod.getName(), vod.getYear(), vod.getPic(), vod.getContent());
    }

    @Override public CompletionStage<Void> play(String handle, long startPositionMs) {
        requireOpen();
        Selection selected = episodes.get(handle);
        if (selected == null) throw new SecurityException("Unknown or retired episode handle");
        site(selected.siteKey());
        active = selected;
        return playSelection(selected, startPositionMs);
    }

    private CompletionStage<Void> playSelection(Selection selected, long position) {
        long playback = ++request;
        firstFrame = false;
        if (manager != null) manager.stop();
        emit(Phase.RESOLVING, "");
        var resultFuture = background(epoch, () -> SiteApi.playerContent(selected.siteKey(), selected.flag().getFlag(),
            selected.episode().getUrl()), result -> {
            if (request != playback) throw new CancellationException("Playback selection superseded");
            ensurePlayer();
            MediaMetadata metadata = new MediaMetadata.Builder().setTitle(selected.vod().getName()).build();
            // Original PlayerManager owns ParseJob, media request creation,
            // engine selection, progress, timeouts and decoder lifecycle.
            manager.parse(selected.siteKey(), result, false, metadata,
                position < 0 ? C.TIME_UNSET : position);
            return (Void) null;
        });
        resultFuture.whenComplete((value, failure) -> main.post(() -> {
            if (!closed && request == playback && failure != null && !(failure instanceof CancellationException))
                emit(Phase.ERROR, "UPSTREAM_PLAYBACK_REQUEST_FAILED");
        }));
        return resultFuture;
    }

    private void ensurePlayer() {
        if (manager != null) return;
        manager = new PlayerManager(new PlayerManager.Callback() {
            @Override public void onPrepare() { attachPlayer(manager.getPlayer()); }
            @Override public void onTracksChanged() { publish(""); }
            @Override public void onDecodeChanged() { publish(""); }
            @Override public void onMediaOptionsChanged() { publish(""); }
            @Override public void onError(String message) { emit(Phase.ERROR, "UPSTREAM_PLAYBACK_ERROR"); }
            @Override public boolean onRefresh() {
                if (closed || active == null) return false;
                playSelection(active, manager.getPosition());
                return true;
            }
            @Override public void onPlayerRebuild(Player player) { attachPlayer(player); }
            @Override public void onDanmakuSourceChanged(Uri uri) { publish(""); }
            @Override public void onDanmakuConfigChanged(DanmakuConfig config) { publish(""); }
            @Override public void onDanmakuEnabledChanged(boolean enabled) { publish(""); }
            @Override public void onDanmakuSent(String text) { publish(""); }
        });
        attachPlayer(manager.getPlayer());
    }

    private final Player.Listener events = new Player.Listener() {
        @Override public void onRenderedFirstFrame() { firstFrame = true; publish(""); }
        @Override public void onPlaybackStateChanged(int state) { updateState(); }
        @Override public void onIsPlayingChanged(boolean playing) { updateState(); }
    };

    private void attachPlayer(Player player) {
        if (observedPlayer != null) observedPlayer.removeListener(events);
        observedPlayer = player;
        if (player != null) player.addListener(events);
        if (view != null && manager != null) manager.bindPlayerView(view);
    }

    /** Called by the existing UI's AndroidView host after cutover. */
    public void bind(PlayerView playerView) {
        requireOpen();
        if (view != null && view != playerView) view.setPlayer(null);
        view = playerView;
        if (manager != null) manager.bindPlayerView(view);
    }

    private void updateState() {
        if (closed || observedPlayer == null) return;
        int state = observedPlayer.getPlaybackState();
        emit(state == Player.STATE_BUFFERING ? Phase.BUFFERING : state == Player.STATE_ENDED ? Phase.ENDED :
            state == Player.STATE_READY ? (observedPlayer.isPlaying() ? Phase.PLAYING : Phase.PAUSED) : Phase.IDLE, "");
    }

    @Override public CompletionStage<Void> pause() { return control(() -> manager.pause()); }
    @Override public CompletionStage<Void> resume() { return control(() -> manager.play()); }
    @Override public CompletionStage<Void> seek(long positionMs) {
        if (positionMs < 0) throw new IllegalArgumentException("Negative seek");
        return control(() -> manager.seekTo(positionMs));
    }
    @Override public CompletionStage<Void> stop() {
        requireOpen();
        ++request;
        active = null;
        releasePlayer();
        emit(Phase.IDLE, "");
        return CompletableFuture.completedFuture(null);
    }
    private CompletionStage<Void> control(Runnable operation) {
        requireOpen();
        if (manager == null) return CompletableFuture.failedFuture(new IllegalStateException("No active playback"));
        operation.run();
        updateState();
        return CompletableFuture.completedFuture(null);
    }
    @Override public void observe(Consumer<State> next) {
        requireOpen();
        observer = java.util.Objects.requireNonNull(next);
        publish("");
    }
    private void emit(Phase next, String code) { phase = next; publish(code); }
    private void publish(String code) {
        if (!closed) observer.accept(new State(request, phase, manager == null ? 0 : manager.getPosition(),
            manager == null ? 0 : manager.getDuration(), firstFrame, code));
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pending.add(future);
        future.whenComplete((value, failure) -> main.post(() -> pending.remove(future)));
        return future;
    }
    private boolean valid(long ticket, CompletableFuture<?> future) {
        if (!closed && ticket == epoch && !future.isCancelled()) return true;
        future.completeExceptionally(new CancellationException("Kernel session retired"));
        return false;
    }
    private void fail(long ticket, CompletableFuture<?> future, String code) {
        if (valid(ticket, future)) {
            emit(Phase.ERROR, code);
            future.completeExceptionally(new IllegalStateException(code));
        }
    }
    @FunctionalInterface private interface Call<T> { T run() throws Exception; }
    private <T, R> CompletableFuture<R> background(long ticket, Call<T> call, java.util.function.Function<T, R> map) {
        var future = track(new CompletableFuture<R>());
        io.execute(() -> {
            try {
                if (closed || ticket != epoch || future.isCancelled()) {
                    future.cancel(false);
                    return;
                }
                T result = call.run();
                main.post(() -> {
                    if (!valid(ticket, future)) return;
                    try { future.complete(map.apply(result)); }
                    catch (CancellationException failure) { future.completeExceptionally(failure); }
                    catch (RuntimeException failure) { future.completeExceptionally(new IllegalStateException("UPSTREAM_RESULT_ERROR")); }
                });
            } catch (Exception failure) { main.post(() -> {
                if (valid(ticket, future)) future.completeExceptionally(new IllegalStateException("UPSTREAM_CALL_FAILED"));
            }); }
        });
        return future;
    }
    private void releasePlayer() {
        if (observedPlayer != null) observedPlayer.removeListener(events);
        observedPlayer = null;
        if (view != null) view.setPlayer(null);
        if (manager != null) manager.release();
        manager = null;
        firstFrame = false;
    }
    @Override public void close() {
        requireMain();
        if (closed) return;
        ++epoch;
        ++request;
        releasePlayer();
        emit(Phase.CLOSED, "");
        closed = true;
        active = null;
        loaded = false;
        movies.clear();
        episodes.clear();
        for (var future : List.copyOf(pending)) future.cancel(false);
        VodConfig.get().cancel();
        Source.get().stop();
        // Serialize destruction behind any in-flight synchronous Spider call.
        // Ownership stays held until it has actually left upstream globals.
        io.execute(() -> {
            try { VodConfig.get().destroy(); Server.get().stop(); }
            finally { main.post(() -> OWNER.compareAndSet(this, null)); }
        });
        io.shutdown();
    }
}
