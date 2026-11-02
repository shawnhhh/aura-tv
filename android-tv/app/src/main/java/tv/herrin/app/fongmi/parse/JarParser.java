/* SPDX-License-Identifier: GPL-3.0-only
 * FongMi/TV fongmi@5e191aaced194e5cac5668cc91908ecb65336222
 * Original api/loader/JarLoader.jsonExt/jsonExtMix.
 * HERRIN changes: injected authorized ClassLoader, class-name validation.
 */
package tv.herrin.app.fongmi.parse;
import java.util.LinkedHashMap;
import java.util.HashMap;
public final class JarParser {
    public static Object invoke(ClassLoader loader, int type, String key, String name, String flag,
        LinkedHashMap<String,String> jxs, LinkedHashMap<String,HashMap<String,String>> mixed, String source) throws Exception {
        if (loader == null) throw new IllegalStateException("Spider JAR not loaded");
        if (type < 2 || type > 3 || !key.matches("[A-Za-z0-9_]{1,80}")) throw new IllegalArgumentException("Invalid parser class");
        Class<?> clazz = loader.loadClass("com.github.catvod.parser." + (type == 2 ? "Json" : "Mix") + key);
        return type == 2 ? clazz.getMethod("parse", LinkedHashMap.class, String.class).invoke(null, jxs, source)
            : clazz.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class)
                .invoke(null, mixed, name, flag, source);
    }
}
