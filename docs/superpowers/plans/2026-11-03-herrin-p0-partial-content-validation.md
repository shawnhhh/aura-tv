# Herrin P0 Partial Content Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent the P0 automated source selector from accepting malformed HTTP 206 responses as proven media.

**Architecture:** Keep the existing OkHttp range probe and source priority unchanged. Replace the permissive Content-Range prefix/substr check with a bounded structural check of a single byte range, including nonnegative positions, start=0 and a consistent size where given. The probe still checks bounded real response bytes and refuses cross-origin credential forwarding.

**Tech Stack:** Kotlin / Android TV, OkHttp MockWebServer, JUnit 4, Gradle, GitHub Actions Debug + TV Emulator.

**Spec:** `docs/superpowers/specs/2026-11-03-herrin-p0-playback-diagnostics-design.md`

## Global Constraints

- Only PR #80's current `codex/m471-tmdb-personal-to-tvbox-playback-20261102` branch; keep Draft and never merge main.
- Never modify the approved Home UI, source authorization, parser allowlist, or Media3 default playback engine.
- Do not log raw URLs, response bodies, credentials, cookies, or private Spider parameters.
- Require observable RED test on a test-only commit before changing implementation; GREEN requires the latest-HEAD Android Debug test+lint+build and 167-item Android TV suite with no failures/skips.
- This improvement addresses a proven *source-probe false positive*, not the real TCL `UNSUPPORTED_OPERATION` which still requires device evidence.

## Review Focus

- A malformed `bytes 0-not-a-number/8192` must be rejected — Task 1.
- A reversed range `bytes 0-9/8` must be rejected — Task 1.
- A valid `bytes 0-15/8192` remains readable — existing test + Task 1.
- A valid unknown-length `bytes 0-15/*` should not be rejected only because total length is unknown — Task 1.
- Missing range metadata on a 206, or a different starting byte, remains invalid — existing tests + Task 1.

---

### Task 1: Make media-byte verification reject forged or malformed Content-Range

**Files:**
- Test: `android-tv/app/src/test/java/tv/herrin/app/M476RemoteMediaProbeTest.kt`
- Modify: `android-tv/app/src/main/java/tv/herrin/app/HerrinRemoteMediaProbe.kt`

**Interfaces:**
- Consumes: HTTP 206 response to a GET with `Range: bytes=0-1023`.
- Produces: `HerrinRemoteMediaProbe.Result.INVALID_RANGE` for syntactically invalid/impossible ranges; preserves `MEDIA` for legitimate 206 with actual video bytes.

- [ ] Step 1: Add a MockWebServer JUnit test that sends a malformed range with a real-looking video body, another with total less than range end, and a valid unknown-total response. Assert `INVALID_RANGE`, `INVALID_RANGE`, `MEDIA` respectively; no real network or private source.
- [ ] Step 2: Commit *only* the test and observe the real Debug workflow failure attributable to the new assertions. Record run and exact test failure.
- [ ] Step 3: Implement the smallest safe parser/check in `HerrinRemoteMediaProbe` (nonnegative numeric start/end; start 0; end >= start; total > end if known), without redirect/auth behavior changes. Avoid unbounded input.
- [ ] Step 4: Re-run full unit suite, lint, Debug APK and Android TV emulator suite for the same new HEAD. Capture output and test counts; fix any real regressions.
- [ ] Step 5: Inspect changed paths to ensure only this plan, probe, and test changed; record run IDs and remaining TCL blocker on Draft PR #80.

### Task 2: Stop at the real-device evidence boundary

**Files:** PR #80 progress comment (not Home UI).

- [ ] Step 1: Review existing controlled Spider MP4/HLS instrumentation evidence vs actual TCL error logs.
- [ ] Step 2: If no real device evidence is available, keep `REAL_TCL_PLAYBACK_BLOCKED`; never claim third-party Spider playback is fixed by HTTP probe tests.
