/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app.fongmi.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import org.junit.Test;

public class ParseJobDeadlineTest {
    @Test public void asynchronousWebViewMustTimeoutEvenAfterWorkerReturns() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        try {
            ParseJob.Host host = new ParseJob.Host() {
                public Parse getParse() { return Parse.get(0, "https://parser.example/?url="); }
                public Parse getParse(String name) { return getParse(); }
                public List<Parse> getParses() { return Collections.emptyList(); }
                public List<Parse> getParses(int type, String flag) { return Collections.emptyList(); }
                public String getSiteClick(String key) { return ""; }
                public ScheduledExecutorService scheduler() { return scheduler; }
                public long timeoutMillis() { return 200; }
                public Call newCall(String url, Map<String, String> headers) { throw new AssertionError("unexpected HTTP"); }
                public Object jsonExt(String key, LinkedHashMap<String, String> jxs, String url) { throw new AssertionError("unexpected JSON"); }
                public Object jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) { throw new AssertionError("unexpected JSON"); }
                public boolean webSupported() { return true; }
                public void post(Runnable task) { task.run(); }
                public AutoCloseable startWeb(String key, String from, Map<String, String> headers, String url, String click, ParseCallback callback, boolean detect) {
                    started.countDown();
                    return closes::incrementAndGet;
                }
                public void onFailure(Throwable cause) { failures.incrementAndGet(); }
            };
            ParseJob job = ParseJob.create(host, new ParseCallback() {
                public void onParseSuccess(Map<String, String> headers, String url, String from) { throw new AssertionError("unexpected success"); }
                public void onParseError() { failed.countDown(); }
            });
            job.start(Result.fromObject("{\"url\":\"https://video.example/1\"}"), false);
            assertTrue("WebView must launch", started.await(2, TimeUnit.SECONDS));
            assertTrue("timeout must fire after worker returns", failed.await(3, TimeUnit.SECONDS));
            assertEquals(1, failures.get());
            assertEquals(1, closes.get());
        } finally {
            scheduler.shutdownNow();
        }
    }
    @Test public void rejectedUiDispatchFailsWithoutWaitingForDeadline() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        try {
            ParseJob.Host host = new ParseJob.Host() {
                public Parse getParse() { return Parse.get(0, "https://parser.example/?url="); }
                public Parse getParse(String name) { return getParse(); }
                public List<Parse> getParses() { return Collections.emptyList(); }
                public List<Parse> getParses(int type, String flag) { return Collections.emptyList(); }
                public String getSiteClick(String key) { return ""; }
                public ScheduledExecutorService scheduler() { return scheduler; }
                public long timeoutMillis() { return 5_000; }
                public Call newCall(String url, Map<String, String> headers) { throw new AssertionError("unexpected HTTP"); }
                public Object jsonExt(String key, LinkedHashMap<String, String> jxs, String url) { throw new AssertionError("unexpected JSON"); }
                public Object jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) { throw new AssertionError("unexpected JSON"); }
                public boolean webSupported() { return true; }
                public void post(Runnable task) {
                    // Reject only the initial WebView launch, not the completion callback.
                    if (failures.get() == 0)
                        throw new IllegalStateException("UI dispatcher unavailable");
                    task.run();
                }
                public AutoCloseable startWeb(String key, String from, Map<String, String> headers, String url, String click, ParseCallback callback, boolean detect) {
                    throw new AssertionError("WebView must not start after dispatch rejection");
                }
                public void onFailure(Throwable cause) { failures.incrementAndGet(); }
            };
            ParseJob job = ParseJob.create(host, new ParseCallback() {
                public void onParseSuccess(Map<String, String> headers, String url, String from) { throw new AssertionError("unexpected success"); }
                public void onParseError() { failed.countDown(); }
            });
            job.start(Result.fromObject("{\"url\":\"https://video.example/1\"}"), false);
            assertTrue("dispatcher rejection must complete without waiting for deadline", failed.await(2, TimeUnit.SECONDS));
            assertEquals(1, failures.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

}
