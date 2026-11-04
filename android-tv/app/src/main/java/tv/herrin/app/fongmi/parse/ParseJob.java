/*
 * Derived from FongMi/TV fongmi@5e191aaced194e5cac5668cc91908ecb65336222.
 * Original: app/src/main/java/com/fongmi/android/tv/player/parse/ParseJob.java
 * SPDX-License-Identifier: GPL-3.0-only
 * HERRIN modifications 2026-11-01: inject host services instead of App,
 * VodConfig, BaseLoader and ParseWebView globals; retain scheduling, parser
 * dispatch, latch, first-success and cancellation control flow.
 */
package tv.herrin.app.fongmi.parse;


import com.github.catvod.utils.Json;
import com.google.common.net.HttpHeaders;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Response;

public class ParseJob implements ParseCallback {

    public interface Host {
        Parse getParse();
        Parse getParse(String name);
        List<Parse> getParses();
        List<Parse> getParses(int type, String flag);
        String getSiteClick(String key);
        java.util.concurrent.ScheduledExecutorService scheduler();
        long timeoutMillis();
        Call newCall(String url, Map<String, String> headers);
        Object jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable;
        Object jsonExtMix(String flag, String key, String name,
            LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable;
        boolean webSupported();
        void post(Runnable task);
        AutoCloseable startWeb(String key, String from, Map<String, String> headers,
            String url, String click, ParseCallback callback, boolean detect);
        default String convertUrl(String url) { return url; }
        default void onFailure(Throwable cause) {}
    }

    private static String fixHeader(String name) {
        if (name.equalsIgnoreCase("ua") || name.equalsIgnoreCase("User-Agent")) return "User-Agent";
        if (name.equalsIgnoreCase("Referer")) return "Referer";
        if (name.equalsIgnoreCase("Cookie")) return "Cookie";
        return name;
    }

    private final AtomicBoolean done = new AtomicBoolean();
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentLinkedQueue<AutoCloseable> webViews;
    private final Host host;
    private ScheduledFuture<?> timeout;
    private ExecutorService executor;
    private ExecutorService infinite;
    private ParseCallback callback;
    private Parse parse;
    private Map<String, String> parseHeaders;
    private String parseClick;

    private ParseJob(Host host, ParseCallback callback) {
        this.host = host;
        this.executor = Executors.newSingleThreadExecutor();
        this.infinite = Executors.newCachedThreadPool();
        this.webViews = new java.util.concurrent.ConcurrentLinkedQueue<>();
        this.callback = callback;
    }

    public static ParseJob create(Host host, ParseCallback callback) {
        return new ParseJob(host, callback);
    }

    public ParseJob start(Result result, boolean useParse) {
        setParse(result, useParse);
        execute(result);
        return this;
    }

    private void setParse(Result result, boolean useParse) {
        if (useParse) parse = host.getParse();
        if (result.getPlayUrl().startsWith("json:")) parse = Parse.get(1, result.getPlayUrl().substring(5));
        if (result.getPlayUrl().startsWith("parse:")) parse = host.getParse(result.getPlayUrl().substring(6));
        if (parse == null || parse.isEmpty()) parse = Parse.get(0, result.getPlayUrl());
        parseHeaders = resolveHeaders(parse, result);
        String click = host.getSiteClick(result.getKey());
        parseClick = click.isEmpty() ? result.getClick() : click;
    }

    static Map<String, String> resolveHeaders(Parse parse, Result result) {
        Map<String, String> configured = parse.getHeader();
        return new HashMap<>(configured.isEmpty() ? result.getHeader() : configured);
    }

    private void execute(Result result) {
        FutureTask<?> task = new FutureTask<>(() -> parseResult(result), null);
        timeout = host.scheduler().schedule(() -> {
            if (task.cancel(true)) {
                host.onFailure(new java.util.concurrent.TimeoutException("Parse deadline"));
                onParseError();
            }
        }, host.timeoutMillis(), TimeUnit.MILLISECONDS);
        executor.execute(task);
    }

    private void parseResult(Result result) {
        try {
            doInBackground(result.getKey(), result.getUrl().v(), result.getFlag());
        } catch (Throwable e) {
            host.onFailure(e);
            onParseError();
        }
    }

    private void doInBackground(String key, String webUrl, String flag) throws Throwable {
        switch (parse.getType()) {
            case 0 -> startWeb(key, parse, webUrl, this);
            case 1 -> jsonParse(parse, webUrl, true);
            case 2 -> jsonExtend(webUrl);
            case 3 -> jsonMix(webUrl, flag);
            case 4 -> superParse(webUrl, flag);
        }
    }

    private void jsonParse(Parse item, String webUrl, boolean fatal) throws Exception {
        Map<String, String> headers = getHeaders(item);
        Call call = host.newCall(item.getUrl() + webUrl, headers);
        calls.add(call);
        try {
            if (done.get()) return;
            try (Response res = call.execute()) {
                readJsonResult(res, headers, item, fatal);
            }
        } catch (IOException e) {
            if (!call.isCanceled()) throw e;
        } finally {
            calls.remove(call);
        }
    }

    private void readJsonResult(Response response, Map<String, String> headers, Parse item, boolean fatal) throws IOException {
        if (!response.isSuccessful()) throw new IOException("Parser HTTP " + response.code());
        if (response.body() == null) throw new IOException("Empty parser response");
        JsonObject object = Json.parse(response.body().string()).getAsJsonObject();
        String url = Json.safeString(object, "url");
        JsonObject data = object.getAsJsonObject("data");
        if (url.isEmpty()) url = Json.safeString(data, "url");
        checkResult(getHeader(object, headers), url, item.getName(), fatal);
    }

    private void jsonExtend(String webUrl) throws Throwable {
        LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
        for (Parse item : host.getParses()) if (item.getType() == 1) jxs.put(item.getName(), item.extUrl());
        checkResult(Result.fromObject(host.jsonExt(parse.getUrl(), jxs, webUrl)));
    }

    private void jsonMix(String webUrl, String flag) throws Throwable {
        LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
        for (Parse item : host.getParses()) jxs.put(item.getName(), item.mixMap());
        checkResult(Result.fromObject(host.jsonExtMix(flag, parse.getUrl(), parse.getName(), jxs, webUrl)));
    }

    private void superParse(String webUrl, String flag) throws Exception {
        List<Parse> json = host.getParses(1, flag);
        List<Parse> webs = host.getParses(0, flag);
        CountDownLatch latch = new CountDownLatch(json.size() + webs.size());
        for (Parse item : json) infinite.execute(() -> jsonParse(latch, item, webUrl));
        ParseCallback webCallback = new ParseCallback() {
            @Override
            public void onParseSuccess(Map<String, String> headers, String url, String from) {
                ParseJob.this.onParseSuccess(headers, url, from);
            }

            @Override
            public void onParseError() {
                latch.countDown();
            }
        };
        for (Parse item : webs) startWeb("", item, webUrl, webCallback);
        latch.await();
        onParseError();
    }

    private void jsonParse(CountDownLatch latch, Parse item, String webUrl) {
        try {
            jsonParse(item, webUrl, false);
        } catch (Exception e) {
            // Failure of one aggregate candidate is nonfatal; never log parser URLs/tokens.
            host.onFailure(e);
        } finally {
            latch.countDown();
        }
    }

    private void checkResult(Map<String, String> headers, String url, String from, boolean fatal) {
        if (!url.isEmpty()) onParseSuccess(headers, url, from);
        else if (fatal) onParseError();
    }

    private void checkResult(Result result) {
        result.setHeader(new HashMap<>(parseHeaders));
        if (result.getUrl().isEmpty()) onParseError();
        else if (result.needParse()) startWeb(result.getHeader(), host.convertUrl(result.getUrl().v()));
        else onParseSuccess(result.getHeader(), result.getUrl().v(), result.getJxFrom());
    }

    private void startWeb(String key, Parse item, String webUrl, ParseCallback callback) {
        startWeb(key, item.getName(), getHeaders(item), item.getUrl() + webUrl, item == parse ? parseClick : item.getClick(), callback);
    }

    private void startWeb(Map<String, String> headers, String url) {
        startWeb("", "", headers, url, "", this);
    }

    private void startWeb(String key, String from, Map<String, String> headers, String url, String click, ParseCallback callback) {
        if (!host.webSupported()) {
            callback.onParseError();
            return;
        }
        host.post(() -> {
            if (done.get()) return;
            try {
                AutoCloseable view = host.startWeb(key, from, headers, url, click, callback, !url.contains("player/?url="));
                // A host may complete synchronously before returning its handle.
                if (view == null) throw new IllegalStateException("WebView host returned no handle");
                if (done.get()) try { view.close(); } catch (Exception ignored) {}
                else {
                    webViews.add(view);
                    // stop() may have drained the queue between the done check
                    // and insertion. Reclaim the late handle exactly once.
                    if (done.get() && webViews.remove(view))
                        try { view.close(); } catch (Exception ignored) {}
                }
            } catch (RuntimeException failure) {
                // WebView startup is dispatched on the UI thread. Without
                // notifying ParseJob, a synchronous host failure strands the
                // parser until its deadline and may crash the main process.
                host.onFailure(failure);
                if (!done.get()) callback.onParseError();
            }
        });
    }

    private Map<String, String> getHeaders(Parse item) {
        return item == parse ? parseHeaders : new HashMap<>(item.getHeader());
    }

    private Map<String, String> getHeader(JsonObject object, Map<String, String> fallback) {
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String name = entry.getKey();
            if (!entry.getValue().isJsonNull() && (name.equalsIgnoreCase(HttpHeaders.USER_AGENT) || name.equalsIgnoreCase(HttpHeaders.REFERER) || name.equalsIgnoreCase(HttpHeaders.COOKIE) || name.equalsIgnoreCase("ua"))) headers.put(fixHeader(name), entry.getValue().getAsString());
        }
        return headers.isEmpty() ? fallback : headers;
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        complete(callback -> callback.onParseSuccess(headers, url, from));
    }

    @Override
    public void onParseError() {
        complete(ParseCallback::onParseError);
    }

    private void complete(Consumer<ParseCallback> result) {
        if (!done.compareAndSet(false, true)) return;
        host.post(() -> deliverResult(result));
    }

    private void deliverResult(Consumer<ParseCallback> result) {
        try {
            if (callback != null) result.accept(callback);
        } finally {
            stop();
        }
    }

    public void stop() {
        done.set(true);
        callback = null;
        if (timeout != null) timeout.cancel(false);
        for (Call call : calls) call.cancel();
        calls.clear();
        if (executor != null) executor.shutdownNow();
        if (infinite != null) infinite.shutdownNow();
        infinite = null;
        executor = null;
        AutoCloseable webView;
        while ((webView = webViews.poll()) != null)
            try { webView.close(); } catch (Exception ignored) {}
    }
}
