/* SPDX-License-Identifier: GPL-3.0-only
 * Adapted from FongMi/TV 305dff233cde512e2c83f71529b7c3ecfd61fefc,
 * api/config/VodConfig.java parser accessors.
 * HERRIN 2026-11-05: process-local playback config without App/DB/UI globals.
 */
package tv.herrin.app.fongmi.parse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parser configuration shared by the production Host and JVM contract tests. */
public final class ParseCatalog {
    private final List<Parse> parsers;

    public ParseCatalog(List<Parse> configured) {
        List<Parse> items = new ArrayList<>();
        // VodConfig.setParses prepends Parse.god() before choosing the default.
        // "Super" is parse_god in the pinned upstream default strings.xml;
        // HERRIN does not expose an upstream parser-selection UI here.
        if (!configured.isEmpty()) {
            Parse supervisor = Parse.get(4, "");
            supervisor.setName("Super");
            items.add(supervisor);
        }
        items.addAll(configured);
        parsers = Collections.unmodifiableList(items);
    }

    public Parse getParse() {
        return parsers.isEmpty() ? Parse.get(0, "") : parsers.get(0);
    }

    public Parse getParse(String name) {
        for (Parse item : parsers) if (item.getName().equals(name)) return item;
        return Parse.get(0, "");
    }

    public List<Parse> getParses() { return parsers; }

    public List<Parse> getParses(int type, String flag) {
        List<Parse> typed = new ArrayList<>();
        List<Parse> matching = new ArrayList<>();
        for (Parse item : parsers) {
            if (item.getType() != type) continue;
            typed.add(item);
            if (item.getExt().getFlag().contains(flag)) matching.add(item);
        }
        return Collections.unmodifiableList(matching.isEmpty() ? typed : matching);
    }
}
