/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app.fongmi.parse;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import okhttp3.Call;
import org.junit.Test;

public class ParseJobStartupFailureTest {
    @Test public void rejectedDeadlineSchedulerReleasesBothWorkerPools() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.shutdownNow();
        ParseJob.Host host = new ParseJob.Host() {
            public Parse getParse() { return Parse.get(0, "https://parser.example/?url="); }
            public Parse getParse(String name) { return getParse(); }
            public List<Parse> getParses() { return Collections.emptyList(); }
            public List<Parse> getParses(int type, String flag) { return Collections.emptyList(); }
            public String getSiteClick(String key) { return ""; }
            public ScheduledExecutorService scheduler() { return scheduler; }
            public long timeoutMillis() { return 1000; }
            public Call newCall(String url, Map<String, String> headers) { throw new AssertionError("unexpected HTTP"); }
            public Object jsonExt(String key, LinkedHashMap<String, String> jxs, String url) { throw new AssertionError("unexpected JSON"); }
            public Object jsonExtMix(String flag, String key, String name,
                LinkedHashMap<String, HashMap<String, String>> jxs, String url) { throw new AssertionError("unexpected JSON"); }
            public boolean webSupported() { return false; }
            public void post(Runnable task) { task.run(); }
            public AutoCloseable startWeb(String key, String from, Map<String, String> headers,
                String url, String click, ParseCallback callback, boolean detect) {
                throw new AssertionError("unexpected WebView");
            }
        };
        ParseJob job = ParseJob.create(host, new ParseCallback() {
            public void onParseSuccess(Map<String, String> headers, String url, String from) { fail("unexpected success"); }
            public void onParseError() { fail("unexpected callback"); }
        });
        Field executorField = ParseJob.class.getDeclaredField("executor");
        Field infiniteField = ParseJob.class.getDeclaredField("infinite");
        executorField.setAccessible(true);
        infiniteField.setAccessible(true);
        ExecutorService worker = (ExecutorService) executorField.get(job);
        ExecutorService aggregate = (ExecutorService) infiniteField.get(job);
        try {
            job.start(Result.fromObject("{\\"url\\":\\"https://video.example/1\\"}"), false);
            fail("rejected scheduler should fail startup");
        } catch (RejectedExecutionException expected) {
            assertTrue(worker.isShutdown());
            assertTrue(aggregate.isShutdown());
        }
    }
}
