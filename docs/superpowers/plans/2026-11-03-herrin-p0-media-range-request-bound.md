# Herrin P0 Requested Range Bound Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent invalid oversized HTTP 206 responses from qualifying as a playable source when the media probe requested only bytes 0–1023.

**Architecture:** Retain the current trusted-provider, range probe and bounded read; validate `Content-Range` as an actual subset of the sent `Range: bytes=0-1023`. Reuse the existing strict Content-Range parser and source-level privacy policy without changing any player or proxy routes.

**Tech Stack:** Kotlin, OkHttp MockWebServer, JUnit, Android Gradle Android Debug, TV emulator API 30.

**Spec:** `docs/superpowers/specs/2026-11-03-herrin-p0-playback-diagnostics-design.md`

## Global Constraints

- Only `shawnhhh/aura-tv` Draft PR #80 branch `codex/m471-tmdb-personal-to-tvbox-playback-20261102`. No merge to main, no UI or navigation changes.
- Keep full existing synthetic Java Spider/Media3 frame tests intact, and do not claim real TCL playback verified.
- No unauthenticated localhost probing; do not log URLs, cookies, tokens or response bodies.
- Test-only RED commit and actual failing Debug CI before production GREEN; latest GREEN must pass full Debug+Lint+assemble and 167/167 TV instrumentation with 0 failures or skips.

## Review Focus

- A 206 `Content-Range: bytes 0-2048/8192` must be rejected when the probe requested `bytes=0-1023`.
- A 206 `bytes 0-1023/8192` must remain valid.
- A 206 `bytes 0-15/*` must remain valid.
- A 206 `bytes 0-15/8` remains rejected, as in prior TDD.
- HTTP 200, redirect policy, and authorization behavior remain unchanged.

---

### Task 1: Pin request-size boundaries for partial media

**Files:**
- Test: `android-tv/app/src/test/java/tv/herrin/app/M476RemoteMediaProbeTest.kt`
- Modify: `android-tv/app/src/main/java/tv/herrin/app/HerrinRemoteMediaProbe.kt`

- [ ] Add MockWebServer 206 oversized-range and valid-boundary checks; verify RED failure on Debug CI.
- [ ] Change strict `Content-Range` check to reject an end offset beyond 1023, without changing its existing validity checks.
- [ ] Run GREEN on same latest HEAD: full unit, Debug APK, lint and emulator 167 cases. Inspect real job logs.
- [ ] Review diff paths, log test and build run IDs in Draft PR #80.

### Task 2: Preserve P0 real-device gate

- [ ] Confirm Draft PR is unmerged and Home UI untouched.
- [ ] Record `REAL_TCL_PLAYBACK_BLOCKED` until actual TCL Spider `route_operation` and Media3 rendered frames are available.
