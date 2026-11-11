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
import com.fongmi.android.tv.model.VideoViewModel;
import com.fongmi.android.tv.playback.PlaybackResult;
import com.fongmi.android.tv.playback.vod.VodDetailResult;
import com.fongmi.android.tv.playback.vod.VodPlayRequest;
import com.fongmi.android.tv.playback.vod.VodPlaybackController;
import com.fongmi.android.tv.playback.vod.VodPlaybackHost;

/** Original view-model/controller binding, including expanded netdisk episodes,
 * original history, preload, replay and selection state. The host implements
 * rendering/transport callbacks; it must not supply a parser or data source.
 *
 * This binding is staged for the final UI migration. It must replace the
 * preliminary Kernel.play delegation, never run beside it for the same movie.
 */
public final class OriginalVodSession implements AutoCloseable {
    private final ViewModelStore store = new ViewModelStore();
    private final VideoViewModel model;
    private final VodPlaybackController controller;
    private boolean closed;
    private final Observer<VodDetailResult> detail;
    private final Observer<PlaybackResult<VodPlayRequest>> playback;
    private final Observer<PlaybackResult<VodPlayRequest>> preload;
    private final Observer<Result> search;

    public OriginalVodSession(LifecycleOwner lifecycle, VodPlaybackHost host) {
        requireMain();
        ViewModelStoreOwner owner = () -> store;
        model = new ViewModelProvider(owner, new ViewModelProvider.NewInstanceFactory()).get(VideoViewModel.class);
        controller = model.createPlaybackController(host);
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
    public void selectLine(Flag flag) { requireOpen(); controller.selectFlag(flag); }
    public void selectEpisode(Episode episode) { requireOpen(); controller.selectEpisode(episode); }
    public void replay() { requireOpen(); controller.replay(); }
    public void nextEpisode() { requireOpen(); controller.nextEpisode(true); }
    public void previousEpisode() { requireOpen(); controller.prevEpisode(true); }
    public void onServiceReady() { requireOpen(); controller.onPlaybackServiceReady(); }
    public void onSeek() { requireOpen(); controller.onPlaybackSeek(); }
    public void onPlayingChanged(boolean playing) { requireOpen(); controller.onPlayingChanged(playing); }
    public void onEnded() { requireOpen(); controller.playbackEnded(); }
    public void onProgress(long time, long position, long duration) {
        requireOpen(); controller.onTimeChanged(time, position, duration);
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
