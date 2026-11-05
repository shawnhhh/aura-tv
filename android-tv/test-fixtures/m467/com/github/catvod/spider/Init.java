// Original HERRIN synthetic test plugin, GPL-3.0-only. No external provider code.
package com.github.catvod.spider;
import android.content.Context;
public final class Init {
    static boolean initialized;
    public static void init(Context context) {
        LifecycleGate.pause(LifecycleGate.state(context), "bootstrap", Init.class.getClassLoader());
        initialized = true;
    }
}
