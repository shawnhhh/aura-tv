# Herrin P0 Playback Evidence and Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct source-specific Spider proxy error attribution, collect safe real failure evidence, and only then close the verified P0 playback gap.

**Architecture:** Keep existing Kotlin `SpiderProxyPlayback` + isolated `:spider` process and Media3. A single failure-selector shared by route-reason and safe-operation diagnosis must follow the same JSON entries/order as normalization and identify the *first rejected proxy*. Do not change source authorization or known operation allowlist.

**Tech Stack:** Kotlin, Android TV, org.json, JUnit, Gradle, GitHub Actions Android Debug and Android TV Emulator API 30.

**Spec:** `docs/superpowers/specs/2026-11-03-herrin-p0-playback-diagnostics-design.md`

## Global Constraints

- Only current Draft PR #80 head branch `codex/m471-tmdb-personal-to-tvbox-playback-20261102`; never merge main or modify accepted homepage UI.
- Existing Spider authorization, loopback token/siteKey, static marker, host/port, untrusted Java/JS/Python policy remain unchanged.
- No raw URL, HTTP Header, credentials, cookies or unknown `do` value may reach UI/logcat.
- A test-only RED commit (expected CI failure) must precede any implementation edit; log its SHA/run. GREEN requires latest same-commit Debug AND TV full suite success.
- Simulated media/HTTP tests do not prove TCL real video frames. Report that blocker accurately.

## Review Focus

- Mixed first-valid/second-invalid proxy lines must diagnose the second, not first — Task 1.
- Mismatched siteKey on a later proxy route must not be swallowed by a valid first — Task 1.
- `url.values` nested `v` options must behave like arrays, including bounded scanning — Task 1.
- Secret query values and unknown-operation strings must not leak to UI/logcat — Task 1.
- Operation health and actual TCL media-byte playback require authorized device evidence — Task 3.

---

### Task 1: Diagnose the exact rejected Spider playerContent route

**Files:**
- Test: `android-tv/app/src/test/java/tv/herrin/app/M472ProxyRouteDiagnosticTest.kt`
- Modify: `android-tv/app/src/main/java/tv/herrin/app/SpiderProxyPlayback.kt`
- Modify: `android-tv/app/src/main/java/tv/herrin/app/TvBoxSpiderBridge.kt`

**Interfaces:**
- Consume: `normalizePlayerResponse(rawJson, siteKey, proxy)`, `rebaseUrl(raw, siteKey, proxy)`.
- Produce: `playerResponseReason(rawJson, siteKey, proxy): String` accurate for the rejected entry, `safeOperation(rawJson, siteKey, proxy): String` for that same entry (API change from the two-arg version).

- [ ] **Step 1: Write the failing JUnit regression first.** Test array `["主线","proxy://do=jar&siteKey=approved","备用","proxy://do=parse&siteKey=approved&token=SECRET"]`: normalization returns null; `playerResponseReason` equals `UNSUPPORTED_OPERATION`, `safeOperation` equals `PARSE`. Test nested `url.values` with first valid and second bad key: reason `SITE_KEY_MISMATCH`; redaction check excludes `SECRET`.
- [ ] **Step 2: Run RED CI on test-only HEAD.** Android Debug Gradle test must fail on wrong first-route reason (or compile error solely from intended new API expectation). Capture workflow job log and exact reason.
- [ ] **Step 3: Implement the minimal fix** by iterating the same bounded JSON URL entries as normalizer and selecting the first proxy for which `rebaseUrl` returns null; both user-facing classifiers inspect that same rejected URL. Keep string-array labels and non-proxy URLs untouched. Update bridge call site to pass current siteKey. Never print raw values.
- [ ] **Step 4: Run GREEN CI** on implementation HEAD. Require both Android Debug full build+unit+Lint and Android TV full suite success; 0 failed/skipped. If red, inspect logs, correct and repeat.
- [ ] **Step 5: Commit and record** tested SHA, log lines, any unsupported route cases.

### Task 2: Check source-to-player evidence boundary without guessing proxy operations

**Files:**
- Inspect: `android-tv/app/src/main/java/tv/herrin/app/SpiderProxyPlayback.kt`
- Inspect: `android-tv/app/src/main/java/tv/herrin/app/HerrinPlayableSourceSelector.kt`
- Inspect: `android-tv/app/src/main/java/tv/herrin/app/HerrinRemoteMediaProbe.kt`
- Inspect: relevant existing JVM and Android instrumentation tests.

**Interfaces:**
- Consume: verified `PlaybackMedia` from authorized Spider, bounded bytes + current session; output an evidence table for P0.
- Produce: only a concrete follow-up patch if an existing failing trace or a independently RED reproducer demonstrates an implementation fault.

- [ ] **Step 1:** Trace each boundary from title match → detail → episode/line → playerContent → proxy → HTTP bytes → Media3 frames; extract real CI logs and prior TCL screenshot evidence.
- [ ] **Step 2:** For any demonstrable defect, write a minimal failing test (RED) before implementation; otherwise write a blocker entry instead of guessing.
- [ ] **Step 3:** If a defect is proven, fix minimally and run full GREEN CI on latest HEAD; otherwise leave unverified items open.

### Task 3: TV reproduction and final verification gate

**Files:**
- Update: this plan's evidence notes, or PR #80 description (no UI changes).

**Interfaces:**
- Consume: latest SHA/CI logs, TV screenshot/ADB diagnostic `HerrinSpiderProxy` and `HerrinPlaybackDiag` (redacted).
- Produce: explicit P0 acceptance status with test counts, true unresolved blockers and no merge.

- [ ] **Step 1:** Confirm the latest HEAD is still the current Draft PR #80 branch, unchanged homepage UI, unmerged.
- [ ] **Step 2:** Confirm latest two CI runs and actual test counts; report precise failures or green only when they finished.
- [ ] **Step 3:** If TCL device evidence isn't available here, state `REAL_TCL_PLAYBACK_BLOCKED` with the exact log/episode proof required. Do not claim finished or manufacture device evidence.
