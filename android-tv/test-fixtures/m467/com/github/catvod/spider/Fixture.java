// Original HERRIN synthetic test plugin, GPL-3.0-only. No external provider code.
package com.github.catvod.spider;
import android.content.Context;
import com.github.catvod.crawler.Spider;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
public final class Fixture extends Spider {
    @Override public void init(Context context, String extend) {
        if (!Init.initialized) throw new IllegalStateException("JAR Init was not invoked");
    }
    @Override public String homeContent(boolean filter) {
        return "{\"list\":[{\"vod_id\":\"fixture\",\"vod_name\":\"Original synthetic fixture\"}]}";
    }
    @Override public String detailContent(List<String> ids) {
        return "{\"list\":[{\"vod_id\":\"fixture\",\"vod_play_from\":\"Synthetic\",\"vod_play_url\":\"Instance$instance#Static$static\"}]}";
    }
    @Override public String playerContent(String flag, String id, List<String> vipFlags) {
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
        headers.put("X-Herrin-Fixture-Route", route);
        return new Object[] {200, "video/mp4", input, headers};
    }
}
