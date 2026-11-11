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
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.playback.vod.VodPlaybackHost;
import com.fongmi.android.tv.playback.vod.VodPlaybackMedia;
import com.fongmi.android.tv.playback.vod.VodPlayRequest;
import com.fongmi.android.tv.db.AppDatabase;
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
public final class FongMiKernel implements Kernel, VodPlaybackHost {
    private static final AtomicReference<FongMiKernel> OWNER = new AtomicReference<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, MovieEntry> movies = new HashMap<>();
    private final Map<String, String> movieHandles = new HashMap<>();
    private final Map<String, Selection> episodes = new HashMap<>();
    private final Map<Selection, String> episodeHandles = new HashMap<>();
    private final List<CompletableFuture<?>> pending = new ArrayList<>();
    private Consumer<State> observer = state -> {};
    private Consumer<Detail> detailObserver = detail -> {};
    private WarehouseAccess access;
    private PlayerManager manager;
    private Player observedPlayer;
    private PlayerView view;
    private Selection active;
    private OriginalVodSession vodSession;
    private final SessionLifecycle lifecycle = new SessionLifecycle();
    private Vod currentVod;
    private List<Flag> currentFlags = List.of();
    private String currentSite = "";
    private String currentId = "";
    private History history;
    private Detail latestDetail;
    private CompletableFuture<Void> playbackAdmission;
    private CompletableFuture<Detail> detailAdmission;
    private long requestedPosition = C.TIME_UNSET;
    private static final class SessionLifecycle implements LifecycleOwner {
        final LifecycleRegistry registry = new LifecycleRegistry(this);
        @Override public Lifecycle getLifecycle() { return registry; }
    }
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
        lifecycle.registry.setCurrentState(Lifecycle.State.RESUMED);
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
        movieHandles.clear();
        episodes.clear();
        episodeHandles.clear();
        releaseVodSession();
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

    @Override public CompletionStage<List<Movie>> home(String siteKey) {
        requireOpen();
        var original = site(siteKey);
        return background(epoch, () -> SiteApi.homeContent(original), result ->
            result.getList().stream().map(vod -> remember(siteKey, vod)).toList());
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
        if (vodSession != null && currentSite.equals(entry.siteKey()) && currentId.equals(entry.vod().getId()) && latestDetail != null)
            return CompletableFuture.completedFuture(latestDetail);
        releaseVodSession();
        releasePlayer();
        currentVod = entry.vod(); currentSite = entry.siteKey(); currentId = currentVod.getId();
        detailAdmission = track(new CompletableFuture<>());
        vodSession = new OriginalVodSession(lifecycle, this,
            key -> !closed && loaded && currentSite.equals(key) && access.approvedSiteKeys().contains(key));
        vodSession.openDetail();
        return detailAdmission;
    }

    private Movie remember(String siteKey, Vod vod) {
        String handle = movieHandles.computeIfAbsent(siteKey + "\u0000" + vod.getId(), key -> UUID.randomUUID().toString());
        movies.put(handle, new MovieEntry(siteKey, vod));
        return new Movie(handle, siteKey, vod.getName(), vod.getYear(), vod.getPic(), vod.getContent());
    }

    @Override public CompletionStage<Void> play(String handle, long startPositionMs) {
        requireOpen();
        Selection selected = episodes.get(handle);
        if (selected == null) throw new SecurityException("Unknown or retired episode handle");
        site(selected.siteKey());
        if (!selected.flag().getEpisodes().contains(selected.episode()))
            throw new IllegalStateException("Episode was replaced by upstream resolution");
        active = selected;
        if (playbackAdmission != null && !playbackAdmission.isDone()) playbackAdmission.cancel(false);
        boolean changingMovie = vodSession == null || currentVod != selected.vod() || !currentSite.equals(selected.siteKey());
        if (changingMovie) releaseVodSession();
        playbackAdmission = track(new CompletableFuture<>());
        requestedPosition = startPositionMs < 0 ? C.TIME_UNSET : startPositionMs;
        if (manager != null) manager.stop();
        ensurePlayer();
        if (changingMovie) {
            currentVod = selected.vod();
            currentSite = selected.siteKey();
            currentId = currentVod.getId();
            vodSession = new OriginalVodSession(lifecycle, this,
                key -> !closed && loaded && currentSite.equals(key) && access.approvedSiteKeys().contains(key));
            vodSession.acceptDetail(currentSite, currentId, Result.vod(currentVod));
        }
        vodSession.allowPlayback();
        // These are the original controller entry points, exactly as an
        // upstream UI line/episode click. The original model cancels displaced
        // requests and the controller handles expanded netdisk episode results.
        vodSession.selectLine(selected.flag());
        vodSession.selectEpisode(selected.episode());
        return playbackAdmission;
    }

    private void ensurePlayer() {
        if (manager != null) return;
        manager = new PlayerManager(new PlayerManager.Callback() {
            @Override public void onPrepare() { attachPlayer(manager.getPlayer()); }
            @Override public void onTracksChanged() { publish(""); }
            @Override public void onDecodeChanged() { publish(""); }
            @Override public void onMediaOptionsChanged() { publish(""); }
            @Override public void onError(String message) {
                emit(Phase.ERROR, "UPSTREAM_PLAYBACK_ERROR");
                if (vodSession != null) vodSession.playbackError(message);
                failAdmission();
            }
            @Override public boolean onRefresh() {
                if (closed || vodSession == null) return false;
                vodSession.refresh();
                return true;
            }
            @Override public void onPreloadError(Object tag) {
                if (vodSession != null && tag instanceof VodPlayRequest original) vodSession.onPreloadError(original);
            }
            @Override public void onPreloadTransition(Object tag, long position, long duration) {
                if (vodSession != null && tag instanceof VodPlayRequest original)
                    vodSession.onPreloadTransition(original, position, duration);
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
        @Override public void onPlaybackStateChanged(int state) {
            updateState();
            if (state == Player.STATE_ENDED && vodSession != null) vodSession.onEnded();
        }
        @Override public void onIsPlayingChanged(boolean playing) {
            updateState();
            if (vodSession != null) vodSession.onPlayingChanged(playing);
        }
    };

    private void attachPlayer(Player player) {
        if (observedPlayer != null) observedPlayer.removeListener(events);
        observedPlayer = player;
        if (player != null) player.addListener(events);
        if (view != null && manager != null) manager.bindPlayerView(view);
        publish("");
    }

    /** Called by the existing UI's AndroidView host after cutover. */
    public void bind(PlayerView playerView) {
        requireOpen();
        if (view != null && view != playerView) { view.setBackendPlayer(null); view.setPlayer(null); }
        view = playerView;
        if (manager != null && view != null) manager.bindPlayerView(view);
    }
    public Player playerForHost() { requireOpen(); ensurePlayer(); return manager.getPlayer(); }
    public void unbindForHost(PlayerView expected) { requireOpen(); if (view == expected) bind(null); }
    public float speedForHost(float value) { requireOpen(); ensurePlayer(); return manager.setSpeed(value); }
    public void repeatForHost(boolean value) { requireOpen(); ensurePlayer(); manager.setRepeatOne(value); }
    public void skipSilenceForHost(boolean value) { requireOpen(); ensurePlayer(); manager.setSkipSilenceEnabled(value); }
    public boolean skipSilenceForHost() { requireOpen(); ensurePlayer(); return manager.isSkipSilence(); }
    public boolean cycleTrackForHost(int type) {
        requireOpen(); ensurePlayer();
        var tracks = manager.getPlayer().getCurrentTracks();
        List<com.fongmi.android.tv.bean.Track> choices = new ArrayList<>();
        int selected = -1;
        int ordinal = 0;
        for (var group : tracks.getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++) {
                var format = group.getTrackFormat(i);
                var choice = com.fongmi.android.tv.player.track.TrackUtil.createTrack(type,
                    format.label == null ? "" : format.label, format, ordinal++);
                if (group.isTrackSelected(i)) selected = choices.size();
                choices.add(choice);
            }
        }
        if (choices.isEmpty()) return false;
        manager.setTrack(choices.get((selected + 1) % choices.size()).key(manager.getKey()));
        return true;
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
        return control(() -> { manager.seekTo(positionMs); if (vodSession != null) vodSession.onSeek(); });
    }
    @Override public CompletionStage<Void> stop() {
        requireOpen();
        ++request;
        active = null;
        releaseVodSession();
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
    @Override public void observeDetail(Consumer<Detail> next) {
        requireOpen();
        detailObserver = java.util.Objects.requireNonNull(next);
        if (latestDetail != null) detailObserver.accept(latestDetail);
    }
    private void emit(Phase next, String code) { phase = next; publish(code); }
    @Override public State snapshot() {
        requireOpen();
        if (vodSession != null && manager != null)
            vodSession.onProgress(System.currentTimeMillis(), manager.getPosition(), manager.getDuration());
        return new State(request, phase, manager == null ? 0 : manager.getPosition(),
            manager == null ? 0 : manager.getDuration(), firstFrame, "");
    }
    private void publish(String code) {
        if (!closed) {
            try { observer.accept(new State(request, phase, manager == null ? 0 : manager.getPosition(),
                manager == null ? 0 : manager.getDuration(), firstFrame, code)); }
            catch (RuntimeException error) { android.util.Log.e("HerrinKernel", "STATE_OBSERVER_FAILED"); }
        }
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
    private void releaseVodSession() {
        if (vodSession != null) {
            if (manager != null) vodSession.saveHistory(true, System.currentTimeMillis(), manager.getPosition(), manager.getDuration());
            vodSession.close();
            vodSession = null;
        }
        if (playbackAdmission != null && !playbackAdmission.isDone()) playbackAdmission.cancel(false);
        if (detailAdmission != null && !detailAdmission.isDone()) detailAdmission.cancel(false);
        currentVod = null;
        currentFlags = List.of();
        history = null;
        latestDetail = null;
    }
    private void failAdmission() {
        if (playbackAdmission != null && !playbackAdmission.isDone())
            playbackAdmission.completeExceptionally(new IllegalStateException("UPSTREAM_PLAYBACK_ERROR"));
    }
    private void refreshDetail() {
        if (currentVod == null) return;
        episodes.entrySet().removeIf(entry -> entry.getValue().vod() == currentVod);
        List<Line> lines = new ArrayList<>();
        for (Flag flag : currentFlags) {
            List<Episode> values = new ArrayList<>();
            for (var episode : flag.getEpisodes()) {
                var selection = new Selection(currentSite, currentVod, flag, episode);
                String handle = episodeHandles.computeIfAbsent(selection, key -> UUID.randomUUID().toString());
                episodes.put(handle, selection);
                values.add(new Episode(handle, episode.getName(), values.size()));
            }
            lines.add(new Line(flag.getShow(), values));
        }
        latestDetail = new Detail(remember(currentSite, currentVod), lines);
        try { detailObserver.accept(latestDetail); }
        catch (RuntimeException error) { android.util.Log.e("HerrinKernel", "DETAIL_OBSERVER_FAILED"); }
    }

    // Original VodPlaybackHost: UI projection and calls to the same original
    // PlayerManager. No playerContent/Source/ParseJob logic is implemented here.
    @Override public String getVodKey() { return currentSite; }
    @Override public String getVodId() { return currentId; }
    @Override public void setVodId(String id) { currentId = id; }
    @Override public String getVodName() { return currentVod == null ? "" : currentVod.getName(); }
    @Override public String getVodPic() { return currentVod == null ? "" : currentVod.getPic(); }
    @Override public String getVodMark() { return ""; }
    @Override public String getHistoryKey() {
        return currentSite + AppDatabase.SYMBOL + currentId + AppDatabase.SYMBOL + VodConfig.getCid();
    }
    @Override public boolean isSiteChangeable() { return false; }
    @Override public boolean isFromCollect() { return false; }
    @Override public boolean isHostFinishing() { return closed; }
    @Override public boolean isPlayerEmpty() { return manager == null || manager.isEmpty(); }
    @Override public boolean hasPlaybackSession() { return manager != null && !manager.isEmpty(); }
    @Override public boolean isLivePlayback() { return false; }
    @Override public boolean canTrackPlaybackProgress() { return manager != null && !manager.isEmpty(); }
    @Override public boolean canPreloadNext() { return manager != null; }
    @Override public boolean isPlayerPlaying() { return manager != null && manager.isPlaying(); }
    @Override public long getPlayerPosition() { return manager == null ? 0 : manager.getPosition(); }
    @Override public long getPlayerDuration() { return manager == null ? 0 : manager.getDuration(); }
    @Override public void usePushId(String id) { throw new SecurityException("Push requires separate source authorization"); }
    @Override public void openPush(String target) { throw new SecurityException("Push requires separate source authorization"); }
    @Override public void onPlaybackRequested() { ++request; firstFrame = false; emit(Phase.RESOLVING, ""); }
    @Override public void prepareSource(Vod item) {
        if (!item.getSiteKey().isEmpty() && !currentSite.equals(item.getSiteKey()))
            throw new SecurityException("Source switch requires explicit host routing");
        currentVod = item; currentFlags = item.getFlags(); currentId = item.getId(); refreshDetail();
    }
    @Override public void stopPlaybackForRefresh() { if (manager != null) manager.stop(); }
    @Override public void resetPlaybackForError(String message) { stopPlaybackForRefresh(); emit(Phase.ERROR, "UPSTREAM_VOD_ERROR"); failAdmission(); }
    @Override public void replay(long position) { requestedPosition = position; if (vodSession != null) vodSession.replay(); }
    @Override public void seekPlayback(long position) { if (manager != null) manager.seekTo(position); }
    @Override public void setPlaybackEnding(long ending) { if (manager != null) manager.setEnding(ending); }
    @Override public boolean isPlaybackEndingManaged() { return true; }
    @Override public void startPlayback(Result result, boolean useParse, long position, MediaMetadata metadata) {
        ensurePlayer();
        long start = requestedPosition == C.TIME_UNSET ? position : requestedPosition;
        requestedPosition = C.TIME_UNSET;
        manager.parse(getHistoryKey(), result, useParse, metadata, start, history == null ? 0 : history.getEnding());
        if (playbackAdmission != null) playbackAdmission.complete(null);
    }
    @Override public boolean preloadPlayback(Result result, long position, MediaMetadata metadata, VodPlayRequest tag) {
        return manager != null && manager.preload(PlaySpec.from(result, getHistoryKey(), metadata)
            .setEnding(history == null ? 0 : history.getEnding()), position, tag);
    }
    @Override public boolean seekToPreloaded(VodPlayRequest tag) { return manager != null && manager.seekToPreloaded(tag); }
    @Override public void clearPreload() { if (manager != null) manager.clearPreload(); }
    @Override public void loadDanmaku(Result result, History current, com.fongmi.android.tv.bean.Episode episode) {
        if (manager != null) VodPlaybackMedia.searchDanmaku(result, current, episode,
            value -> { if (manager != null) manager.setDanmaku(value); },
            value -> { if (manager != null) manager.addDanmaku(value); });
    }
    @Override public void renderDetail(Vod item, History current) {
        currentVod = item; currentFlags = item.getFlags(); history = current; refreshDetail();
        if (detailAdmission != null) detailAdmission.complete(latestDetail);
    }
    @Override public void renderVodUpdate(Vod item) { currentVod = item; refreshDetail(); }
    @Override public void renderEmptyDetail() {
        latestDetail = null; emit(Phase.ERROR, "EMPTY_DETAIL"); failAdmission();
        if (detailAdmission != null) detailAdmission.completeExceptionally(new IllegalStateException("EMPTY_DETAIL"));
    }
    @Override public void renderFallbackName(String name) { if (currentVod != null) { currentVod.setName(name); refreshDetail(); } }
    @Override public void renderFlags(List<Flag> items) { currentFlags = items; refreshDetail(); }
    @Override public void renderEpisodes(List<com.fongmi.android.tv.bean.Episode> items) { refreshDetail(); }
    @Override public void renderFlagSelection(Flag item) { publish(""); }
    @Override public void renderEpisodeSelection(com.fongmi.android.tv.bean.Episode item) { publish(""); }
    @Override public void renderReverseEpisodes(List<com.fongmi.android.tv.bean.Episode> items, boolean scroll) { refreshDetail(); }
    @Override public void renderQuality(Result result, boolean visible) { publish(""); }
    @Override public void renderQualityVisible(boolean visible) { publish(""); }
    @Override public void renderSources(List<Vod> items) { publish(""); }
    @Override public void renderHistory(History current) { history = current; publish(""); }
    @Override public void renderUseParse(boolean enabled) { publish(""); }
    @Override public void renderArtwork(String url) { if (currentVod != null) { currentVod.setPic(url); refreshDetail(); } }
    @Override public void renderDescription(String desc) { publish(""); }
    @Override public void renderPlaybackMetadata(MediaMetadata metadata) { if (manager != null) manager.setMetadata(metadata); }
    @Override public void onDetailFallbackScheduled() { publish(""); }
    @Override public void onDetailFallbackCancelled() { publish(""); }
    @Override public void onSearchStarted(String keyword) { publish(""); }
    @Override public void onSearchResult() { publish(""); }
    @Override public void showDetailMessage(String message) { publish(""); }
    @Override public void showSwitchLine(Flag item) { publish(""); }
    @Override public void showSwitchSource(Vod item) { site(item.getSiteKey().isEmpty() ? currentSite : item.getSiteKey()); publish(""); }
    @Override public void showNoNext(boolean reversed) { emit(Phase.ENDED, ""); }
    @Override public void showNoPrev(boolean reversed) { publish(""); }
    @Override public void finishVod() { failAdmission(); releasePlayer(); emit(Phase.IDLE, ""); }
    @Override public void close() {
        requireMain();
        if (closed) return;
        ++epoch;
        ++request;
        releaseVodSession();
        releasePlayer();
        emit(Phase.CLOSED, "");
        closed = true;
        observer = state -> {};
        detailObserver = detail -> {};
        view = null;
        lifecycle.registry.setCurrentState(Lifecycle.State.DESTROYED);
        active = null;
        loaded = false;
        movies.clear();
        movieHandles.clear();
        episodes.clear();
        episodeHandles.clear();
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
