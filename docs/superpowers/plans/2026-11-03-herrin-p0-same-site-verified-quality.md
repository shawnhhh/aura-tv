# Herrin P0: Highest Verified Offer Within One Approved Spider Site

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans with real RED/GREEN tests.

**Goal:** If a single approved Spider site advertises up to three movie matches, choose the highest *actually validated episode/line* quality, not the first item with a playable link or the highest catalog headline.

**Root cause (code read):** `HerrinApprovedSpiderBatch.scan` sorts `session.search` results by `resolutionClaim`, takes three, then immediately `return@withTimeoutOrNull Match(...)` for the first offer where `session.pick` succeeds. This makes lower advertised catalog claims but higher actually playable video lines invisible to auto-selection. A later site cannot repair an arbitrarily missed superior offer from the same site.

**Scope:** `shawnhhh/aura-tv`, existing Draft PR #80, branch `codex/m471-tmdb-personal-to-tvbox-playback-20261102`; preserve accepted homepage, trusted/pinned Spider restrictions, Media3, diagnostics, proxy allowlist and never merge.

## Task 1: TDD on verified same-site quality

**Files:**
- Test `android-tv/app/src/test/java/tv/herrin/app/M472ApprovedSpiderBatchTest.kt`
- Implement `android-tv/app/src/main/java/tv/herrin/app/HerrinApprovedSpiderBatch.kt`

- [ ] Test-only commit: site returns candidate A claimed 4K but the selected/validated line is 720p, candidate B claimed 1080p and the selected/validated line is 1080p. Assert both were checked and chosen candidate B has claim 1080 and is active after restore.
- [ ] RED: observe expected JUnit assertion in Android Debug CI `M472ApprovedSpiderBatchTest`; unrelated test failures require investigation.
- [ ] GREEN: for each approved site, compare all of its **at most three** results by actual `session.pick`-verified line `selectedLineClaim`, then submit the best for existing session reactivation/verification. Preserve separate per-site timeout and bounded global scan. Keep `candidatesWithEpisodes` semantics.
- [ ] Verify latest-HEAD Android Debug unit/Lint/assemble and Android TV emulator 167 full tests; 0 failures/skips. Inspect real logs.
- [ ] Diff paths and record tested SHAs/run IDs in Draft PR.

## Task 2: Review and blockers

- [ ] Verify only the plan/test/scan algorithm changed; no UI edits, no main merge.
- [ ] Preserve **REAL_TCL_PLAYBACK_BLOCKED**; synthetic JVM Spider fixtures do not prove authorized real third-party playerContent works or Media3 renders frames on TCL.
