// Compile-only ABI declaration; NEVER packaged into the fixture DEX.
package com.github.catvod.crawler;
import android.content.Context;
import java.util.List;
import java.util.Map;
public abstract class Spider {
    public String siteKey;
    public void init(Context context, String extend) throws Exception {}
    public String homeContent(boolean filter) throws Exception { return ""; }
    public String detailContent(List<String> ids) throws Exception { return ""; }
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception { return ""; }
    public Object[] proxy(Map<String, String> params) throws Exception { return null; }
}
