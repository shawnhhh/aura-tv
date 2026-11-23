/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app.spiderdialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import com.github.catvod.Runtime;

/** Android services for the relocated original dialog; no authorization logic.
 * 未测试，待后续统一验证。 */
public final class DialogHost {
    public static final class App {
        private static final Handler MAIN = new Handler(Looper.getMainLooper());
        public static void post(Runnable action) { MAIN.post(action); }
    }
    public static final class ResUtil {
        public static int dp2px(int dp) {
            return Math.round(dp * Resources.getSystem().getDisplayMetrics().density);
        }
    }
    public static final class Util {
        public static void copy(String text) {
            android.app.Activity activity = Runtime.activity();
            if (activity == null) return;
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("", text));
        }
    }
    private DialogHost() {}
}
