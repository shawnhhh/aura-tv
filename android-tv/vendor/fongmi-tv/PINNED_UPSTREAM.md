# FongMi/TV playback-core replacement: upstream source lock

Upstream project: https://github.com/FongMi/TV
Branch: `fongmi`
Pinned commit: `305dff233cde512e2c83f71529b7c3ecfd61fefc`
License: GPL-3.0. Original authors retain their copyright.
Local GPL text: `android-tv/LICENSE-GPL-3.0.md`.

This folder contains **verbatim upstream source**, staged outside
`app/src/main` so it cannot silently replace the playable production build.
Run `python3 android-tv/scripts/verify_fongmi_vendor.py` to check the bytes
against Git blob SHAs from the pinned upstream commit.

Imported now:
- `api/loader/BaseLoader.java`
- `api/loader/JarLoader.java`
- `player/parse/ParseJob.java`
- `server/process/Proxy.java`

Not yet imported or integrated: upstream VodConfig/Result/Parse/HTTP server,
WebView host, PlayerManager, PlaySpec, MediaItemFactory, engine/exo sources and
their dependencies. This staging is **not** an app playback implementation.
Neither the old playback core nor its runtime call sites have been removed.
Do not claim P0 complete or a TCL playback fix from this import.
