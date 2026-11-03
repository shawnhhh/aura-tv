# Herrin P0 JSON Response False-positive Probe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Do not auto-select expired or unparsed video URLs returning JSON data instead of actual media, even when an origin advertises `application/octet-stream` or `video/mp4`.

**Architecture:** Keep the authorized OkHttp range probe and 206 bound checks, redirect security, player engine, source routing, and loopback policies intact. In the existing bounded 2048-byte body sniff, classify strongly JSON-looking responses as text/control payloads before `MEDIA` is returned; do not log or retain bodies. This does not parse or execute any JSON from a third-party origin.

**Tech Stack:** Kotlin, OkHttp MockWebServer, JUnit, Android Debug Gradle and Android TV API 30 emulator.

**Spec:** `docs/superpowers/specs/2026-11-03-herrin-p0-playback-diagnostics-design.md`

## Global Constraints

- Only current Draft PR #80 branch `codex/m471-tmdb-personal-to-tvbox-playback-20261102`; do not merge main or edit accepted home UI.
- No change to Spider operation allowlist, trusted-source authorization or FongMi runtime.
- Never log raw body, URL, token, cookie, player-content fields or private HTTP headers.
- RED test-only commit must fail for the intended assertion on CI before production change; GREEN must pass complete Debug+Lint+assemble and TV emulator 167 required tests with zero failures and skips.
- Do not treat synthetic HTTP tests as physical TCL playback evidence.

## Review Focus

- `{"code":403,"message":"expired"}` returned as `application/octet-stream` must fail the media gate.
- `{"url":"https://provider.example/video.mp4"}` as `video/mp4` is a JSON parser response, not media bytes.
- A genuine range-response video byte prefix must still be accepted under the existing 206 rules.
- Valid `#EXTM3U` and `<MPD` must remain detectable, regardless of their actual MIME when existing behavior allows.
- Redirect authentication/privacy and localhost session checks must remain unchanged.

---

### Task 1: Pin disguised JSON player payloads in deterministic test

**Files:**
- Test: `android-tv/app/src/test/java/tv/herrin/app/M476RemoteMediaProbeTest.kt`
- Modify: `android-tv/app/src/main/java/tv/herrin/app/HerrinRemoteMediaProbe.kt`

- [ ] Add MockWebServer response fixtures for JSON code, URL JSON and binary MP4, in one short JUnit case; assert `LOGIN_OR_TEXT` for JSON and `MEDIA` for bytes.
- [ ] Commit test only, run Debug CI and capture exact failed method/line; if it does not fail for expected reason, investigate before touching implementation.
- [ ] Make minimal bounded JSON-envelope detection in `probe` before the generic `MEDIA` fallback; leave 206 validation and authorization unchanged.
- [ ] Run the latest-HEAD complete unit suite, Debug APK, Lint and Android TV emulator 167/167. If failure, inspect actual logs and repeat.
- [ ] Compare commit to baseline, record evidence in Draft PR #80.

### Task 2: Preserve source-level acceptance boundary

- [ ] Confirm Draft PR still open and homepage UI untouched.
- [ ] Keep `REAL_TCL_PLAYBACK_BLOCKED` pending known third-party Spider `route_operation`, exact failure stage and real TCL video frames.
