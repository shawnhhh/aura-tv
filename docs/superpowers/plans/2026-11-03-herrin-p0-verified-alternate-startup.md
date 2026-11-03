# Herrin P0 — Start Playback When a Provider-supplied Alternate Is the Only Readable Address

> **Superpowers:** systematic-debugging → writing-plans → test-driven-development → executing-plans → verification-before-completion.

**Goal:** Do not reject an otherwise playable authorized TVBox/CMS/Spider line just because the first URL from its `playerContent` result has expired while a separately supplied alternate is readable.

**Root cause from real production source:** `SpiderVodCodec.player` retains provider supplied `alternateUris`. `HerrinPlayableSourceSelector.find`, used by `MainActivity` for TVBox/CMS and approved batch Spider, calls `verifyMedia(media)` **only once per line**, on `media.uri`. A failed primary returns `null` without testing that same provider's explicit backups. Thus fallback is never passed to Media3 and no playback begins. The existing `PlaybackFormatRetry.Session` protects only the already-open player, too late for source-gate failure.

**Files and design:**
- `android-tv/app/src/test/java/tv/herrin/app/M476PlayableSourceSelectorTest.kt` — deterministic regression test of failed primary + working authorized backup and forbidden auth cross-origin.
- `android-tv/app/src/main/java/tv/herrin/app/HerrinPlayableSourceSelector.kt` — bounded per-line verification of primary then provider-supplied backup(s). Return the verified URL as `Selected.media.uri`, keep only downstream unused backups if any; never forward already failed URLs. Keep episode/line identity.
- No change to `SpiderProxyPlayback`, Spider operation allowlists, the approved runtime boundary, or accepted Home UI.

## Task 1: Test-first source gate

- [ ] Add JUnit RED fixtures: primary GET is dead, provider backup works; `find` must return `Selected` pointing to verified alternate. Do not confuse simple candidate existence with verification.
- [ ] Assert bounded verification: no more than eight addresses, no duplicate attempts, no bad primary reinserted as fallback.
- [ ] Protect credentials: only probe cross-origin backup with sensitive original headers if the origin is identical; otherwise skip. The provider may offer cross-origin public alternates with no sensitive headers.
- [ ] Commit test-only and observe the exact failure (not compile/setup) on GitHub Actions Android Debug.

## Task 2: Minimal implement and verify

- [ ] Iterate the provider-provided alternate addresses within each line, retain original in-line `resolve` limits, and return playable media with the verified URI promoted to primary.
- [ ] Ensure sensitive header scoped alternate logic and per-source authorization; no arbitrary URL guessing or extra Spider operations.
- [ ] Run latest HEAD Android Debug unit/lint/assemble plus Android TV API 30 full 167 instrumentation tests, inspect original job logs, and ensure 0 failed/skipped.
- [ ] Compare exact changed files and post the RED/GREEN evidence to existing Draft PR #80.

## Blockers and acceptance

- This addresses a reproducible **playerContent to source-selection** issue and enables an already-resolved authorized backup to start. It **does not** prove third-party TCL Spider routes work.
- Real TCL `SOURCE_PROXY_INVALID_ROUTE/UNSUPPORTED_OPERATION` is still unresolved without safe operation classification and physical device Media3 frame evidence. Do not guess new allowed proxy operations, merge PR/main, or change accepted homepage UI.
