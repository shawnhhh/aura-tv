# Herrin P0 Parallel Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking. Execution already authorized; A/B/C/D use isolated worktrees and root alone integrates.

**Goal:** Route selected Herrin episodes through one native playback entry without changing the approved UI.

**Architecture:** A owns the authorized loader and worker, B owns NativeParseJob and native proxy, C owns the concrete Media3 PlayerManager behind AuthorizedPlaybackPanel. D adds a thin selection/authorization adapter and wires MainActivity to these contracts. The adapter returns existing PlaybackResult/PlaybackMedia; player frame/error events remain owned by the existing container and C's manager.

**Tech Stack:** Kotlin, Android API 26+, Compose, official Media3 1.9.4, JUnit4, Android instrumentation.

**Spec:** docs/superpowers/plans/2026-11-03-herrin-p0-replace-playback-with-fongmi-core.md

## Global Constraints

- Upstream FongMi baseline: 305dff233cde512e2c83f71529b7c3ecfd61fefc; GPL-3.0 attribution and modifications preserved.
- No homepage/TMDB layout edits; MainActivity edits confined to playback resolution.
- Never fetch/run external provider JAR/JS/Python; checked-in original synthetic fixtures only.
- No old proxy whitelist patches, double parsing, or second loopback proxy.
- Root is sole integrator and remote writer; keep PR80 Draft; no main/PR merges.

## Review Focus

- A cached Spider selection from a different approved site must fail before worker dispatch.
- Missing episode/line metadata must fail rather than choosing another selection.
- Direct personal media must keep source headers/subtitles and avoid parsers.
- CMS watch pages must use NativeParseJob once while signed extensionless streams remain direct.
- Disposed/retried playback must preserve existing history and remote-control callbacks.

### Task 1: Selection and authorization contract

**Files:** Create android-tv/app/src/main/java/tv/herrin/app/HerrinPlaybackAdapter.kt and android-tv/app/src/test/java/tv/herrin/app/HerrinPlaybackAdapterTest.kt.

**Interfaces:** Produces HerrinPlaybackAdapter.resolve(request: HerrinPlaybackRequest): PlaybackResult. Request contains Film, exact episode/line, selected source (Spider site key/CMS/Direct), authorization (approved Spider site key), and optional preauthorized personal PlaybackMedia. Consumes suspend source callbacks returning PlaybackResult. Spider callback delegates existing TvBoxSpiderBridge.play unchanged.

- [ ] Write tests rejecting a mismatched/missing approved site and missing selection, and verifying exact direct media/subtitles and one Spider dispatch.
- [ ] Run :app:testDebugUnitTest --tests tv.herrin.app.HerrinPlaybackAdapterTest; observe missing-contract RED.
- [ ] Implement validation and single dispatch without media re-parsing.
- [ ] Run the same tests for GREEN, then commit after root confirms remote HEAD.

### Task 2: Single production entry

**Files:** Modify HerrinPlaybackEntry.kt and playback-only sections of MainActivity.kt; create HerrinCmsPlaybackEntryTest.kt.

**Interfaces:** Consumes NativeParseJob.resolve(context, playerContent, flag, jsonParsers, allowHttp, siteHeaders, playbackParsers, ...defaults): PlaybackResult from B. Consumes existing remote bridge play/approvedActiveSiteKey from A. Produces PlaybackMedia for unchanged AuthorizedPlaybackPanel from C.

- [ ] Write tests that CMS selection emits native player content for an HTML/configured parser route, preserves parse=0 for a signed direct URL, and strips site API credentials.
- [ ] Run these tests and observe RED before implementation.
- [ ] Implement CMS player-content policy and replace MainActivity CMS Json/Browser composables with adapter-driven asynchronous NativeParseJob resolution; use the same adapter for Spider and direct media.
- [ ] Run both unit classes and the complete :app:testDebugUnitTest suite; attempt :app:assembleDebug and :app:assembleDebugAndroidTest. Report every failure honestly.

### Task 3: Executable fixture integration and chain review

**Files:** Create android-tv/app/src/androidTest/java/tv/herrin/app/HerrinPlaybackAdapterEndToEndTest.kt.

**Interfaces:** Consumes checked-in m467-spider.jar, synthetic.mp4, bridge browse/details, new adapter, and existing PlaybackTestActivity hosting C's actual player container/manager.

- [ ] Write original synthetic DEX -> adapter -> native proxy HTTP bytes -> existing container decoded-frame instrumentation test; reject mismatched approval before execution.
- [ ] Compile instrumentation and run connected test if emulator is available. Do not claim executed frame acceptance without a real run.
- [ ] Review production entry for residual FongMiParsePlayback, old proxy, second CMS parser, stale selection and fallback paths. Report exact integration work outside D ownership to root.
- [ ] Write /workspace/herrin-coordination/d-report.md with RED/GREEN commands, failures, files, SHAs, and residual runtime/device blockers. Root performs final integrated verification.
