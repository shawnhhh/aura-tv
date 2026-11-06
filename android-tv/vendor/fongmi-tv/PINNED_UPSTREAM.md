# Original FongMi/TV playback source staged for Herrin P0

Status update (2026-11-04): the staging-time migration statements below are
historical. The legacy proxy has since moved to test-only source sets; production
uses an authorized native proxy and a host-adapted ParseJob. PlayerManager and
the full custom Media3 dependency graph remain unintegrated. See the current
[production compatibility audit](../../../docs/p0/compatibility-matrix.md).

Original source: https://github.com/FongMi/TV
Upstream branch: `fongmi`; pinned revision `305dff233cde512e2c83f71529b7c3ecfd61fefc`.
License: GPL-3.0; preserve upstream copyrights and required notices.
GPL text in this repository: `android-tv/LICENSE-GPL-3.0.md`.

This directory holds exact original upstream files, intentionally located
outside `app/src/main`, so it does NOT alter the current build or run source
code before the dependency graph is ported and validated.

Imported: 43 source files: BaseLoader, JarLoader, ParseJob, Proxy,
PlayerManager, two player-engine files, five player-media files and ten
Exo-specific files, plus 21 upstream VOD/bean/HTTP/WebView integration files. The SHA1 manifest `UPSTREAM_SHA1.txt` lists each exact
upstream Git blob. Enforced in Android Debug CI via
`android-tv/scripts/verify_fongmi_vendor.py`.

Still missing for a complete production migration: upstream model and config
classes, native HTTP server lifecycle, browser host, MPV/engine dependencies,
audio/video effect/track modules, customized Media3 build, and a minimal
Herrin playback UI adapter. As of this staging, Herrin's legacy
SpiderProxyPlayback is still the active production path. Do not infer true
TVBox playback or real TCL decoding from a clean source-integrity check.
