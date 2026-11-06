/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app.fongmi.parse;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import org.junit.Test;

/** The Activity can disappear after WebView startup but before result dispatch. */
public class ParseJobCompletionDispatchTest {
    @Test public void rejectedCompletionDispatchReleasesWorkersAndActiveWebView() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger posts = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        ParseJob.Host host = new ParseJob.Host() {
            public Parse getParse() { return Parse.get(0, ""); }
            public Parse getParse(String name) { return getParse(); }
            public List<Parse> getParses() { return Collections.emptyList(); }
            public List<Parse> getParses(int type, String flag) { return Collections.emptyList(); }
            public String getSiteClick(String key) { return ""; }
            public ScheduledExecutorService scheduler() { return scheduler; }
            public long timeoutMillis() { return 30_000; }
            public Call newCall(String url, Map<String, String> headers) { throw new AssertionError("unexpected HTTP"); }
            public Object jsonExt(String key, LinkedHashMap<String, String> jxs, String url) { throw new AssertionError("unexpected JSON"); }
            public Object jsonExtMix(String flag, String key, String name,
                LinkedHashMap<String, HashMap<String, String>> jxs, String url) { throw new AssertionError("unexpected JSON"); }
            public boolean webSupported() { return true; }
            public void post(Runnable task) {
                // Exercise the same boolean-returning queue bridge as Android.
                ParseUiDispatcher.post(queued -> {
                    if (posts.incrementAndGet() > 1) return false;
                    queued.run();
                    return true;
                }, task);
                ready.countDown(); // The returned WebView handle is now registered.
            }
            public AutoCloseable startWeb(String key, String from, Map<String, String> headers,
                    String url, String click, ParseCallback callback, boolean detect) {
                return closes::incrementAndGet;
            }
        };
        ParseJob job = ParseJob.create(host, new ParseCallback() {
            public void onParseSuccess(Map<String, String> headers, String url, String from) { fail("rejected UI cannot deliver success"); }
            public void onParseError() { fail("must not deliver an off-UI fallback callback"); }
        });
        ExecutorService worker = pool(job, "executor");
        ExecutorService aggregate = pool(job, "infinite");
        try {
            job.start(Result.fromObject("{\"url\":\"https://video.example/film.mp4\"}"), false);
            assertTrue("WebView must be registered", ready.await(3, TimeUnit.SECONDS));
            try {
                job.onParseSuccess(Collections.emptyMap(), "https://video.example/film.mp4", "");
                fail("dispatch rejection must remain visible to the caller");
            } catch (RejectedExecutionException expected) {
                assertTrue("parser worker must shut down immediately", worker.isShutdown());
                assertTrue("aggregate pool must shut down immediately", aggregate.isShutdown());
                assertEquals("active WebView must close exactly once", 1, closes.get());
            }
            job.stop();
            assertEquals("repeated cancellation must not close twice", 1, closes.get());
        } finally {
            job.stop();
            scheduler.shutdownNow();
        }
    }

    private ExecutorService pool(ParseJob job, String name) throws Exception {
        Field field = ParseJob.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ExecutorService) field.get(job);
    }
}
