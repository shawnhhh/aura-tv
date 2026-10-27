# M3.7 — Local live channel search

An optional **查找频道** button on the Live tab expands a native TV text field.
Users enter a channel name or IPTV `tvg-id` with the system TV keyboard and
see matching channels in the existing channel rail. Filtering happens entirely
in memory and never fetches another network response or searches stream URLs.

- Name and `tvg-id` matching are case-insensitive, Unicode-aware and ignore
  surrounding whitespace; the typed query is limited to 40 characters.
- The search intersects the existing `group-title` selector and per-M3U
  favorites filter without reordering channels, changing favorites or writing
  searches to disk. An all-groups search can find any channel; when a named
  group is selected it searches only that group.
- The result counter changes from “显示” to “匹配” when a keyword is present.
  Empty results explicitly suggest clearing the keyword or changing group /
  favorites filters.
- Both the search toggle and its clear button are native focusable D-pad
  controls, with focus identities for M2.5's navigation underline fade.
  Closing the search clears the keyword. Saving a changed M3U URL clears
  search state so a previous source cannot silently hide new channels.
- A pure JVM test covers Unicode and ASCII matching, `tvg-id`, query trim,
  intersections, source order, and avoidance of matching private stream URLs.
  An Android TV instrumentation test uses a synthetic HTTPS M3U and asserts
  filtering as text changes without further playlist requests.

Debug version: `0.3.7-alpha` / code 7. The app still requires cloud Debug,
Lint, unit tests and full API30 emulator acceptance, plus physical TCL T7L
Ultra checks of the native system keyboard and D-pad return-to-channel flow.
No providers, third-party streams or FongMi GPL code are bundled.
