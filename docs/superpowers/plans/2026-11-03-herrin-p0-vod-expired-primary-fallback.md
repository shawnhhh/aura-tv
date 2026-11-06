# Herrin P0: Allow Explicit Header-free VOD Backup After 404/410

> Superpowers: systematic-debugging → writing-plans → test-driven-development → executing-plans → verification-before-completion.

**Root cause:** `AuthorizedPlaybackPanel` uses `PlaybackFormatRetry.Session`, but for ordinary video the session's `allowNotFoundFallback` defaults off. `SpiderVodCodec.player` already supplies explicit backup URLs in `PlaybackMedia.alternateUris` and never marks header-free authorized backup lists as `safeNotFoundFallback`. Consequently, a dead HTTP 404/410 primary can stop the player even if its *same response* contains another URL. This is independent from provider source-gate verification; both must work to start actual playback.

**Goal:** If a Java Spider playerContent returns at least two normalized playable HTTP(S) media addresses and **no origin-specific request headers**, opt that one selected VOD media session into bounded 404/410 failover among only those explicit addresses; preserve HTTP 401/403/429 denial safeguards, source authorization and proxy allowlists.

**Code paths:** `android-tv/app/src/main/java/tv/herrin/app/TvBoxSpiderBridge.kt` (`SpiderVodCodec.player`); `android-tv/app/src/test/java/tv/herrin/app/M477SpiderVodFallbackTest.kt` (RED/GREEN).

## Task 1 — RED

- [ ] Test result for two header-free authorized HTTPS addresses enables `safeNotFoundFallback`; `PlaybackFormatRetry.Session` uses it to move to the backup on HTTP 404 and 410.
- [ ] Test with no alternate URL stays disabled, and with Authorization/Cookie headers stays disabled. HTTP 403/429 never cause automatic backup requests.
- [ ] Commit only test, run Android Debug and confirm failing method/line rather than a build/setup failure.

## Task 2 — GREEN

- [ ] In `SpiderVodCodec.player` set `safeNotFoundFallback` true only for 2+ normalized provider URLs and no supplied headers. Do not invent URLs, access arbitrary domains, relax Spider operations or inject auth.
- [ ] Run latest-HEAD Android Debug unit tests, Lint, APK build, Android TV API 30 instrumentation all 167 tests. Inspect original job logs; no skip/fail.
- [ ] Preserve P0 branch Draft PR #80; no homepage edits and no merge; log exact RED/GREEN evidence.

**Not proof of real TCL third-party Spider playback:** exact `SOURCE_PROXY_INVALID_ROUTE/UNSUPPORTED_OPERATION` cause and rendered frames require real device source logs. Do not overstate.
