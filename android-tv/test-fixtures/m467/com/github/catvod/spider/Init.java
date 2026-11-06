// Original HERRIN synthetic test plugin, GPL-3.0-only. No external provider code.
package com.github.catvod.spider;
import android.content.Context;
public final class Init {
    static boolean initialized;
    static String proxyUrl;
    public static void init(Context context) {
        LifecycleGate.count(LifecycleGate.state(context), "bootstraps");
        LifecycleGate.pause(LifecycleGate.state(context), "bootstrap", Init.class.getClassLoader());
        try {
            proxyUrl = (String) Class.forName("com.github.catvod.Proxy")
                .getMethod("getUrl", boolean.class).invoke(null, true);
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        initialized = true;
    }
}
