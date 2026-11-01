// Original HERRIN synthetic test plugin, GPL-3.0-only.
package com.github.catvod.spider;
import java.util.Map;
public final class Proxy {
    public static Object[] proxy(Map<String, String> params) throws Exception {
        if (params.containsKey("siteKey")) throw new SecurityException("Static route received siteKey");
        return Fixture.bytes(params, "static");
    }
}
