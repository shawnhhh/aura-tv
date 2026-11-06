# Herrin P0 playback compatibility — evidence-led design

**Date:** 2026-11-03
**Authority:** User's explicit P0 requirements; Draft PR #80; TCL T7L Ultra screenshots showing Spider route rejection; current GitHub Actions and source code.

## Intent and goals

Preserve the accepted TMDB-first Herrin UI while bringing an **already-authorized** TVBox/FongMi/CatVod media source through actual selected-title search, episodes, playerContent, safe local proxy, bounded media-byte verification and Media3 video frames. Search/poster/URL success alone does not count as playback. Minimize per-site manual selection and reject mismatched or unauthorized cross-site sessions.

## Constraints and non-goals

- Work only on `codex/m471-tmdb-personal-to-tvbox-playback-20261102`, existing **Draft PR #80**. Never merge main/PR, alter accepted homepage navigation or switch default Media3 engine.
- Respect source authorization: no untrusted JAR, JS/Python execution, credential leakage, forged loopback sessions, arbitrary proxy operation enablement, or unauthorized content endpoints.
- Do not claim 4K from filenames or claim TCL success from emulator tests.
- No guessed fix for `SOURCE_PROXY_INVALID_ROUTE / UNSUPPORTED_OPERATION` without the exact failing raw operation / phase from the *user's* TV logs.

## Approaches

1. **Rejected:** add more permissive `do=` aliases based on guesses. Earlier guesses (csp/static operations) left TCL failure unchanged and increase proxy attack surface.
2. **Chosen:** evidence-first bug reproduction. Correct which entry gets blamed in a multi-line Spider response, log safe source/operation classifications, verify route and bounded media bytes under the actual approved session, then fix only proven protocol discrepancies.
3. **Future alternative:** integrate an upstream-approved FongMi runtime adapter if screenshots/trace prove a structural ABI/protocol gap; review licensing and isolated-process trust first.

## Current verified evidence

- HEAD `b6bb9399ded9c213097c35bd6082d4a3f6837fe5` Android Debug and Android TV Emulator CI succeeded; TV instrumentation showed 167/167 pass, 0 skipped/failed (CI run 41564620559). These are **controlled** tests, not TCL real media results.
- TCL's last reported class: `SOURCE_PROXY_INVALID_ROUTE`, `route_reason=UNSUPPORTED_OPERATION`; UI trace also reported `SPIDER PLAYER_CONTENT_REJECTED`. Actual raw `do` is not in screenshot.
- Code defect found: `SpiderProxyPlayback.playerResponseReason()` and `safeOperation()` select the **first** proxy in a JSON array/object, while `normalizePlayerResponse()` may reject a **later** URL. The user-visible error can misidentify the failing operation.

## Acceptance evidence

1. A red then green regression on a first-valid/second-invalid Spider response, for JSON array and `url.values` shapes, with correct rejected route reason and allowed diagnostic operation only.
2. Full latest-HEAD Debug+Lint+unit-test CI success and TV API 30 full instrumentation without failures or skips; no test weakened or skipped.
3. An actual TCL capture showing the operation type/phase for the failing source, followed by an evidence-supported compatibility patch and a repeatable **real TV video frame** acceptance. If no device logs available, leave (3) open, not green.

## Review focus

Multi-route source order, cross-site/mismatched key, forged local proxy sessions, token redaction, stale Spider session after multi-node scans, alternate media URLs with different content types.
