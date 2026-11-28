/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.kernel.android;

import android.os.Looper;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModelStoreOwner;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.model.VideoViewModel;
import com.fongmi.android.tv.playback.PlaybackResult;
import com.fongmi.android.tv.playback.vod.VodDetailResult;
import com.fongmi.android.tv.playback.vod.VodPlayRequest;
import com.fongmi.android.tv.playback.vod.VodPlaybackController;
import com.fongmi.android.tv.playback.vod.VodPlaybackHost;
import com.fongmi.android.tv.playback.vod.VodDataSource;
import com.fongmi.android.tv.playback.vod.VodPlaybackState;
import java.util.List;
import java.util.function.Predicate;

/** Original view-model/controller binding, including expanded netdisk episodes,
 * original history, preload, replay and selection state. The host implements
 * rendering/transport callbacks; it must not supply a parser or data source.
 *
 * The data-source wrapper enforces the host's site authorization only. All
 * operations are still executed by the original VideoViewModel and SiteApi.
 */
public final class OriginalVodSession implements AutoCloseable {
    private final ViewModelStore store = new ViewModelStore();
    private final VideoViewModel model;
    private final VodPlaybackController controller;
    private boolean closed;
    private boolean playbackAllowed;
    private final Observer<VodDetailResult> detail;
    private final Observer<PlaybackResult<VodPlayRequest>> playback;
    private final Observer<PlaybackResult<VodPlayRequest>> preload;
    private final Observer<Result> search;

    public OriginalVodSession(LifecycleOwner lifecycle, VodPlaybackHost host, Predicate<String> approvedSite) {
        requireMain();
        ViewModelStoreOwner owner = () -> store;
        model = new ViewModelProvider(owner, new ViewModelProvider.NewInstanceFactory()).get(VideoViewModel.class);
        VodDataSource authorized = new VodDataSource() {
            private void check(String key) {
                if (!approvedSite.test(key)) throw new SecurityException("VOD source not authorized");
            }
            @Override public void detailContent(String key, String id) { check(key); model.detailContent(key, id); }
            @Override public void detailContent(String key, String id, boolean builtin) {
                check(key); model.detailContent(key, id, builtin);
            }
            @Override public void cancelContent() { model.cancelContent(); }
            @Override public void playerContent(VodPlayRequest request) {
                check(request.getKey());
                // HERRIN opens details before the user presses Play. Preserve
                // original controller selection state, but defer execution.
                if (playbackAllowed) model.playerContent(request);
            }
            @Override public void resolveContent(VodPlayRequest request, Result result) {
                check(request.getKey()); model.resolveContent(request, result);
            }
            @Override public void preloadContent(VodPlayRequest request) {
                if (!playbackAllowed) throw new IllegalStateException("Preload requires user playback admission");
                check(request.getKey()); model.preloadContent(request);
            }
            @Override public void cancelPreloadContent() { model.cancelPreloadContent(); }
            @Override public void searchContent(List<Site> sites, String keyword, boolean quick) {
                // Never hand an empty list to the upstream search controller:
                // its default-site behavior may traverse unapproved warehouses.
                // Search remains on the original VideoViewModel path.
                List<Site> allowed = sites.stream().filter(site -> approvedSite.test(site.getKey())).toList();
                if (allowed.isEmpty()) throw new SecurityException("No approved site for VOD search");
                model.searchContent(allowed, keyword, quick);
            }
        };
        controller = new VodPlaybackController(host, authorized, new VodPlaybackState());
        detail = value -> { if (!closed && value != null) controller.onDetailResult(value); };
        playback = value -> { if (!closed && value != null) controller.onPlaybackResult(value); };
        preload = value -> { if (!closed && value != null) controller.onPreloadResult(value); };
        search = value -> { if (!closed && value != null) controller.onSearchResult(value); };
        model.getDetail().observe(lifecycle, detail);
        model.getPlayback().observe(lifecycle, playback);
        model.getPreload().observe(lifecycle, preload);
        model.getSearch().observe(lifecycle, search);
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
    }
    private void requireOpen() {
        requireMain();
        if (closed) throw new IllegalStateException("VOD session closed");
    }
    public void openDetail() { requireOpen(); controller.checkId(); }
    public void beginSelection() {
        requireOpen();
        playbackAllowed = false;
        // Cancel old worker results before the original controller changes its
        // pending request. Keep its detail/history/expanded-episode state.
        model.cancelContent();
        model.cancelPreloadContent();
    }
    public void allowPlayback() { requireOpen(); playbackAllowed = true; }
    public void acceptDetail(String key, String id, Result result) {
        requireOpen(); controller.onDetailResult(new VodDetailResult(key, id, result));
    }
    public void selectLine(Flag flag) { requireOpen(); controller.selectFlag(flag); }
    public void selectEpisode(Episode episode) { requireOpen(); controller.selectEpisode(episode); }
    public void replay() { requireOpen(); controller.replay(); }
    public void refresh() { requireOpen(); controller.refresh(); }
    public void playbackError(String message) { requireOpen(); controller.playbackError(message); }
    public void nextEpisode() { requireOpen(); controller.nextEpisode(true); }
    public void previousEpisode() { requireOpen(); controller.prevEpisode(true); }
    public void onServiceReady() { requireOpen(); controller.onPlaybackServiceReady(); }
    public void onSeek() { requireOpen(); controller.onPlaybackSeek(); }
    public void onPlayingChanged(boolean playing) { requireOpen(); controller.onPlayingChanged(playing); }
    public void onEnded() { requireOpen(); controller.playbackEnded(); }
    public void onProgress(long time, long position, long duration) {
        requireOpen(); controller.onTimeChanged(time, position, duration);
    }
    public void saveHistory(boolean exit, long time, long position, long duration) {
        requireOpen(); controller.saveHistory(exit, time, position, duration);
    }
    public void onPreloadError(VodPlayRequest request) { requireOpen(); controller.onPreloadError(request); }
    public void onPreloadTransition(VodPlayRequest request, long position, long duration) {
        requireOpen(); controller.onPreloadTransition(request, position, duration);
    }
    @Override public void close() {
        requireMain();
        if (closed) return;
        closed = true;
        model.getDetail().removeObserver(detail);
        model.getPlayback().removeObserver(playback);
        model.getPreload().removeObserver(preload);
        model.getSearch().removeObserver(search);
        controller.reset();
        store.clear();
    }
}
