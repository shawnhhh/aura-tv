# M3.5 — Remote-first IPTV groups, filtering and manual refresh

The Live tab remains driven only by the user's explicit HTTPS M3U URL.

- Show a horizontally scrolling D-pad group selector when multiple `group-title` values are present. The first option means all groups; an empty group is labeled "未分组" without colliding with the all-groups sentinel.
- Preserve the playlist's channel and first-seen group order. Group selection composes with the existing per-playlist hashed favorites filter. Show a visible count and a specific empty state when an intersection has no matches. The original playlist and private favorites are unchanged.
- Provide explicit refresh of channels and optional XMLTV from the live rail. Network requests do **not** run when moving focus between groups; only initial configuration and user refresh initiate fetches.
- A failed same-address refresh retains the last in-memory channel/guide snapshot with an honest failure status. Editing the M3U or XMLTV endpoint clears old data before fetching a different source. An empty/failed playlist can be retried from the Live tab.
- `LiveChannelDiscovery` is a pure local filter with JVM tests covering mixed/missing groups, order, favorite intersection, and stale selection after a source change.
- Debug version: `0.3.5-alpha` / code 5.

Acceptance: cloud Android Debug lint/unit/APK and full API30 TV emulator tests, followed by TCL T7L Ultra checks of group-row scrolling, one-white-focus semantics, rapid D-pad motion, refresh and Back from live playback. No provider, stream, credentials, GPL FongMi source or repository-root Pages change is included. Physical TCL refresh rate and subjective smoothness remain unverified by emulator.
