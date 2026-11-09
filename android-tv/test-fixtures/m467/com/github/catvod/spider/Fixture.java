// Original HERRIN synthetic test plugin, GPL-3.0-only. No external provider code.
package com.github.catvod.spider;
import android.content.Context;
import com.github.catvod.crawler.Spider;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
public final class Fixture extends Spider {
    private Map<String, Object> lifecycle;
    private Context fixtureContext;
    @Override public void init(Context context, String extend) {
        if (!Init.initialized) throw new IllegalStateException("JAR Init was not invoked");
        fixtureContext = context;
        lifecycle = LifecycleGate.state(context);
        LifecycleGate.count(lifecycle, "inits");
        LifecycleGate.pause(lifecycle, "init", this);
    }
    @Override public void destroy() {
        LifecycleGate.count(lifecycle, "destroys");
    }
    @Override public String homeContent(boolean filter) {
        LifecycleGate.count(lifecycle, "homes");
        return "{\"list\":[{\"vod_id\":\"fixture\",\"vod_name\":\"Original synthetic fixture\"}]}";
    }
    @Override public String searchContent(String keyword, boolean quick) {
        if ("__HERRIN_TEST_BLOCK_MAIN__".equals(keyword)) {
            // Original test-only plugin: the child cannot process CANCEL IPC.
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try { new java.io.File(fixtureContext.getFilesDir(), "p0-fixture-main-blocked").createNewFile(); }
                catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                for (;;) { try { Thread.sleep(1000); } catch (InterruptedException ignored) {} }
            });
            for (;;) { try { Thread.sleep(1000); } catch (InterruptedException ignored) {} }
        }
        if ("__HERRIN_TEST_IGNORE_INTERRUPT__".equals(keyword)) {
            try { new java.io.File(fixtureContext.getFilesDir(), "p0-fixture-hang-entered").createNewFile(); }
            catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            // Test-only original DEX fixture, not production provider code/JNI.
            for (;;) { try { Thread.sleep(1000); } catch (InterruptedException ignored) {} }
        }
        LifecycleGate.count(lifecycle, "searches");
        return homeContent(false);
    }
    @Override public String detailContent(List<String> ids) {
        return "{\"list\":[{\"vod_id\":\"fixture\",\"vod_play_from\":\"Synthetic$$$Alternate\",\"vod_play_url\":\"Instance$instance#Static$static$$$Instance$instance#Static$static\"}]}";
    }
    @Override public String playerContent(String flag, String id, List<String> vipFlags) {
        if (id.equals("static")) return "{\"parse\":0,\"format\":\"mp4\",\"url\":\"" +
            Init.proxyUrl + (Init.proxyUrl.contains("?") ? "&" : "?") + "do=jar&part=video\"}";
        return "{\"parse\":0,\"format\":\"mp4\",\"url\":\"proxy://do=jar&part=video" +
            (id.equals("static") ? "" : "&siteKey=" + siteKey) + "\"}";
    }
    @Override public Object[] proxy(Map<String, String> params) throws Exception {
        if (!siteKey.equals(params.get("siteKey"))) throw new SecurityException("Instance site mismatch");
        return bytes(params, "instance");
    }
    static Object[] bytes(Map<String, String> params, String route) throws Exception {
        InputStream input = Fixture.class.getClassLoader().getResourceAsStream("m467-original.mp4");
        if (input == null) throw new IllegalStateException("Synthetic media resource missing");
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Language", route.equals("static") ? "en" : "zh");
        return new Object[] {200, "video/mp4", input, headers};
    }
}
