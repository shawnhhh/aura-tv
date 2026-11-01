/*
 * Source: FongMi/TV fongmi@5e191aaced194e5cac5668cc91908ecb65336222
 * player/exo/ExoMediaSourceFactory.java. Original author: FongMi. GPL-3.0.
 * See android-tv/LICENSE-GPL-3.0.md.
 * 2026-11-01 host ABI adaptation: inject the per-session DataSource/Context;
 * retain createMediaSource concatenation verbatim. Missing custom Media3/libass,
 * shared-cache and content-type resolver paths are listed in docs/m467/player-engine.md.
 */
package com.fongmi.android.tv.player.exo;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;

import com.fongmi.android.tv.player.media.ConcatenatingUrl;

import android.net.Uri;
import java.util.List;

/** Compatible source implementation; unavailable native features are never reported available. */
@androidx.media3.common.util.UnstableApi
public final class ExoMediaSourceFactory implements MediaSource.Factory {
    private final DefaultMediaSourceFactory defaultMediaSourceFactory;

    public ExoMediaSourceFactory(DataSource.Factory dataSourceFactory) {
        defaultMediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory,
                createDefaultExtractorsFactory());
    }

    @NonNull
    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider provider) {
        // Preserve actual platform DRM verification through the host's provider.
        defaultMediaSourceFactory.setDrmSessionManagerProvider(provider);
        return this;
    }

    @NonNull
    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy policy) {
        defaultMediaSourceFactory.setLoadErrorHandlingPolicy(policy);
        return this;
    }

    @NonNull
    @Override
    public @C.ContentType int[] getSupportedTypes() {
        return defaultMediaSourceFactory.getSupportedTypes();
    }

    @NonNull
    @Override
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
        List<ConcatenatingUrl.Segment> segments = ConcatenatingUrl.parse(mediaItem.localConfiguration.uri.toString());
        MediaSource.Factory factory = defaultMediaSourceFactory;
        if (segments.isEmpty()) return factory.createMediaSource(mediaItem);
        ConcatenatingMediaSource2.Builder builder = new ConcatenatingMediaSource2.Builder().setMediaItem(mediaItem);
        for (ConcatenatingUrl.Segment segment : segments) builder.add(factory.createMediaSource(mediaItem.buildUpon().setUri(Uri.parse(segment.url())).build()), segment.durationMs());
        return builder.build();
    }

    static DefaultExtractorsFactory createDefaultExtractorsFactory() {
        return new DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10);
    }
}
