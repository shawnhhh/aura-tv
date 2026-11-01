/* SPDX-License-Identifier: GPL-3.0-only
 * FongMi/TV fongmi@5e191aaced194e5cac5668cc91908ecb65336222
 * Original bean/Parse.java. HERRIN changes: playback-only fields, Gson instance
 * instead of App.gson, no Android Diffable/selection UI, JVM Base64 for Util. */
package tv.herrin.app.fongmi.parse;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class Parse {
    private String name = "";
    private int type;
    private String url = "";
    private Ext ext = new Ext();
    private String click = "";
    public static Parse get(int type, String url) {
        Parse parse = new Parse(); parse.type = type; parse.url = url; return parse;
    }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getType() { return type; }
    public String getUrl() { return url; }
    public String getClick() { return click; }
    public void setClick(String click) { this.click = click; }
    public Ext getExt() { return ext; }
    public Map<String,String> getHeader() { return ext.getHeader(); }
    public boolean isEmpty() { return type == 0 && url.isEmpty(); }
    public String extUrl() {
        int index = getUrl().indexOf("?");
        if (getExt().isEmpty() || index == -1) return getUrl();
        return getUrl().substring(0, index + 1) + "cat_ext=" +
            Base64.getUrlEncoder().encodeToString(getExt().toString().getBytes(StandardCharsets.UTF_8)) +
            "&" + getUrl().substring(index + 1);
    }
    public HashMap<String,String> mixMap() {
        HashMap<String,String> map = new HashMap<>();
        map.put("type", Integer.toString(getType())); map.put("ext", getExt().toString());
        map.put("url", getUrl()); return map;
    }
    public static final class Ext {
        private List<String> flag;
        private Map<String,String> header;
        public List<String> getFlag() { return flag == null ? Collections.emptyList() : flag; }
        public Map<String,String> getHeader() { return header == null ? new HashMap<>() : header; }
        public void setFlag(List<String> flag) { this.flag = new ArrayList<>(flag); }
        public void setHeader(Map<String,String> header) { this.header = new HashMap<>(header); }
        public boolean isEmpty() { return getFlag().isEmpty() && getHeader().isEmpty(); }
        public String toString() { return new Gson().toJson(this); }
    }
}
