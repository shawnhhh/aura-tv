/* SPDX-License-Identifier: GPL-3.0-only
 * FongMi/TV fongmi@5e191aaced194e5cac5668cc91908ecb65336222
 * Original bean/Result.java; playback-only projection of fields consumed by
 * ParseJob. Catalog, movie metadata and UI models deliberately stay in host. */
package tv.herrin.app.fongmi.parse;
import com.google.gson.*;
import java.util.*;
public final class Result {
    private String key = "", flag = "", playUrl = "", click = "", jxFrom = "", url = "";
    private int parse, jx;
    private Map<String,String> header = new HashMap<>();
    public static Result fromObject(Object value) {
        JsonObject object = JsonParser.parseString(String.valueOf(value)).getAsJsonObject();
        Result result = new Result();
        result.key = str(object,"key"); result.flag = str(object,"flag");
        result.playUrl = str(object,"playUrl"); result.click = str(object,"click");
        result.jxFrom = str(object,"jxFrom");
        result.parse = number(object,"parse"); result.jx = number(object,"jx");
        JsonElement urls = object.get("url");
        if (urls != null && urls.isJsonPrimitive()) result.url = urls.getAsString();
        else if (urls != null && urls.isJsonObject()) {
            JsonObject obj = urls.getAsJsonObject(); JsonArray entries = obj.getAsJsonArray("values");
            int position = number(obj,"position");
            if (entries != null && entries.size() > 0) {
                position = position >= 0 && position < entries.size() ? position : 0;
                result.url = str(entries.get(position).getAsJsonObject(),"v");
            }
        } else if (urls != null && urls.isJsonArray() && urls.getAsJsonArray().size() > 0)
            result.url = urls.getAsJsonArray().get(0).getAsString();
        JsonElement headers = object.get("header");
        if (headers != null && headers.isJsonPrimitive()) {
            try { headers = JsonParser.parseString(headers.getAsString()); } catch (RuntimeException ignored) {}
        }
        if (headers != null && headers.isJsonObject()) for (Map.Entry<String,JsonElement> e : headers.getAsJsonObject().entrySet())
            if (e.getValue().isJsonPrimitive()) result.header.put(e.getKey(), e.getValue().getAsString());
        return result;
    }
    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key); return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
    private static int number(JsonObject o, String key) {
        try { return o.has(key) ? o.get(key).getAsInt() : 0; } catch (RuntimeException ignored) { return 0; }
    }
    public String getKey() { return key; }
    public String getFlag() { return flag; }
    public void setFlag(String flag) { this.flag = flag; }
    public String getPlayUrl() { return playUrl; }
    public String getClick() { return click; }
    public String getJxFrom() { return jxFrom; }
    public Map<String,String> getHeader() { return header; }
    public void setHeader(Map<String,String> header) { this.header = header; }
    public Url getUrl() { return new Url(url); }
    public boolean needParse() { return parse == 1 || jx == 1; }
    public int getJx() { return jx; }
    public static final class Url {
        private final String value;
        Url(String value) { this.value = value; }
        public String v() { return value; }
        public boolean isEmpty() { return value.isEmpty(); }
    }
}
