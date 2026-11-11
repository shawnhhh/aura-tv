/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.kernel;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** UI boundary. No playerContent JSON, parser, proxy or media URI crosses it. */
public interface Kernel extends AutoCloseable {
    record WarehouseAccess(URI warehouse, boolean allowCleartext, boolean executeSpider,
                           List<String> approvedSiteKeys) {
        public WarehouseAccess {
            Objects.requireNonNull(warehouse);
            approvedSiteKeys = List.copyOf(approvedSiteKeys);
            String scheme = warehouse.getScheme();
            if (warehouse.getHost() == null || warehouse.getUserInfo() != null ||
                !("https".equalsIgnoreCase(scheme) || allowCleartext && "http".equalsIgnoreCase(scheme)))
                throw new IllegalArgumentException("Warehouse requires explicit HTTP(S) authorization");
            if (!executeSpider || approvedSiteKeys.isEmpty())
                throw new IllegalArgumentException("Explicit site and execution authorization required");
        }
    }
    record Site(String key, String name) {}
    record Movie(String handle, String siteKey, String title, String year, String artwork, String synopsis) {}
    record Episode(String handle, String title, int index) {}
    record Line(String name, List<Episode> episodes) {
        public Line { episodes = List.copyOf(episodes); }
    }
    record Detail(Movie movie, List<Line> lines) {
        public Detail { lines = List.copyOf(lines); }
    }
    enum Phase { IDLE, CONFIGURING, READY, RESOLVING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR, CLOSED }
    /** renderedFirstFrame only comes from the actual player's video event. */
    record State(long request, Phase phase, long positionMs, long durationMs,
                 boolean renderedFirstFrame, String errorCode) {}

    CompletionStage<List<Site>> load(WarehouseAccess access);
    CompletionStage<List<Movie>> home(String siteKey);
    CompletionStage<List<Movie>> search(String siteKey, String query);
    CompletionStage<Detail> details(String movieHandle);
    /** Completes on admission to original PlayerManager, never on playback
     * success. Observe actual frame/progress events separately. */
    CompletionStage<Void> play(String episodeHandle, long startPositionMs);
    CompletionStage<Void> pause();
    CompletionStage<Void> resume();
    CompletionStage<Void> seek(long positionMs);
    CompletionStage<Void> stop();
    State snapshot();
    void observe(Consumer<State> observer);
    void observeDetail(Consumer<Detail> observer);
    @Override void close();
}
