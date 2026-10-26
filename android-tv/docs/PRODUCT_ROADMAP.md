# HERRIN Android TV feature-completion roadmap

The intended product is an original Apple TV+-inspired Android TV interface
for TCL T7L Ultra with legal user-supplied catalogs and streams. A completed
task requires implementation, tests, a cloud-built APK and physical-TV checks
where hardware behavior matters. Draft PRs remain independent and stacked;
owner review is required before merge.

| Stage | User-visible deliverable | Acceptance |
| --- | --- | --- |
| M1–M2.4 | TV UI, original art, launch, catalogs, player and resume | Cloud regression and APK complete |
| M2.5 | Responsive top-tab gliding focus and reduced motion | Fast opposite D-pad movement, emulator screenshot, TCL feel |
| M2.6 | Rename/edit/delete sources, JSON backup/merge import | Atomic invalid-input handling, profile isolation |
| M2.7 | Player reliability and user controls | Buffering/errors, resume, subtitles/tracks, timeout/back tests |
| M2.8 | Live TV from user-authorized sources | Explicit IPTV entries, EPG display, playback/focus, offline fallback |
| M2.9 | Discovery and library management | TV-friendly filtering, search, browsing, favorites and history |
| M3.0 | Robust settings and diagnostics | Storage/cache controls, error reporting, accessibility, settings persistence |
| M3.1 | Release hardening | Reproducible signing/update path, full regression, TCL refresh/perf validation |
| FongMi decision | Optional GPL-3.0 compatibility layer | Audit all dependencies and AAR licenses; decide project distribution license before shipping derivative code |

HERRIN does not ship unauthorized films, scraper providers, user credentials
or proprietary artifacts. FongMi/TV compatibility may require the owner's
license/distribution decision; no source-copying or AAR bundling before that.
The TV's advertised 120Hz panel does not by itself prove 120fps UI rendering.
