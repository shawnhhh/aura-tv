/* SPDX-License-Identifier: GPL-3.0-only
 * FongMi/TV fongmi@5e191aaced194e5cac5668cc91908ecb65336222
 * Original impl/ParseCallback.java; modification: Android package only. */
package tv.herrin.app.fongmi.parse;
import java.util.Map;
public interface ParseCallback {
    void onParseSuccess(Map<String, String> headers, String url, String from);
    void onParseError();
}
