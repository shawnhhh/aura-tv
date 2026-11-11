/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.kernel.android;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Startup;
import com.fongmi.hook.Hook;

/** Host lifecycle only. Original App still initializes the CatVod runtimes. */
public class KernelApplication extends App {
    @Override public void onCreate() {
        super.onCreate();
        new Startup().create(this);
    }

    @Override public void setHook(Hook hook) {
        // No package/signature impersonation, including optional extractors.
        if (hook != null) throw new SecurityException("Package identity substitution is not authorized");
        super.setHook(null);
    }
}
