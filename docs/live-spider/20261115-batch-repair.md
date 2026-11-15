# 2026-11-15 P0 batch repair (not stable / real playback blocked)

Base ee0c4694, branch codex/herrin-p0-live-playback-20261110. Keep PR85 Draft, no merge.

Implemented:
- TmdbBrowseState: deferred first-page refresh preserves already appended pages; retry remembers the failed page instead of retrying the previous successful page. Two deterministic JVM regressions reproduce the state transitions. Added repeated native D-pad/category/detail-return Android regression. These are identified state bugs, **not a claim of a measured remote crash root cause**.
- AuthorizedPlaybackPanel / KernelMinimalControls: use the approved minimal chrome on normal production Media3 playback; same player/session; title/back/settings, progress/time, seek/pause, speed/audio/subtitle. Settings retain line/episode/technical info. Existing diagnostic activity can still show test-only legacy diagnostics. Four-second hide; original aspect ratio unchanged. Final follow-up removes production danmaku rendering and makes physical Back return directly to details (a modal settings sheet still consumes its own Back). The obsolete overlay-first Back test now checks direct return. Legacy session now supports explicit persisted track selection, required by the shared controls. Updated obsolete consumer UI assertions.
- PlaybackDecoderEvidence: collect actual initialized video/audio decoder names and error-time format/container MIME, RFC6381 codec/profile/level, dimensions/fps, SDK/model and allowlisted cause category. Unknown stays unknown; 4003 means runtime decoding failure, not proof of hardware failure. No automatic compatibility fallback added without evidence.
- HTTPS management and HTTP source input remain independent. Extended existing trusted TLS pairing test with exact http://www.饭太硬.net/tv, notification and fresh Preferences persistence checks. This test verifies pairing/save/notification/persistence, **not actual external config import/site activation**.

Evidence limits:
- No connected TV/adb in this workspace; no new actual remote crash stack, source 4003, or QR event captured. Do not mark those fixed.
- Prior Cheese upstream403→host502, separate SIGILL/SIGSEGV, Wanou empty responses remain the recorded real results. No unchanged denied URLs or native-crashing source replayed. No fabricated headers/auth/QR.
- Restore progress by a once-only native ARM64 diagnostic run after a new legal source/auth response or matching native crash symbols/device evidence. Use existing documented disposable-device preparation; do not clear a user's TV configuration.
- Retained ee0c4694 and its recent→details→explicit continue→player→details→recent regression. Existing full TV suite includes P0RecentPlaybackReturnTest and MP4/HLS sixty-second original-media baseline. Original media does not validate Fantaiying.

Local verification: static pinned-source/single-player checks passed; four playback-boundary Python tests passed. Gradle assemble attempted with writable /tmp Gradle home; sandbox socket restriction blocked distribution download (no local SDK/device). Full verification and ARM64 internal diagnostic artifacts requested in the single final CI batch. See current Actions status, not this file, for execution results. No acceptance release.
