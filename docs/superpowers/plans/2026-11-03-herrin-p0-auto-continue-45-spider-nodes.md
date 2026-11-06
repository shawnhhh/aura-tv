# Herrin P0 — One-tap automatic search across all 45 authorized Spider nodes

> Superpowers workflow: systematic-debugging → writing-plans → test-driven-development → executing-plans → verification-before-completion.

**Observed real TCL behavior (2026-11-03):** the film-detail page says `已尝试本批 6 个授权 Spider 影视点（可用节点总数 45），没有通过播放地址验证的线路；点击重新检索可继续下一批`. There are 45 eligible approved sites but the app stops after trying 6, without finding a byte-verified media URI. A manual retry is required for every next group.

**Root cause from production code:** `MainActivity` calls `HerrinApprovedSpiderBatch.scanAcrossPages` with defaults `maxPages=4`, `maxWallClockMs=30_000`. With 6 nodes per page, each permitted up to 4.5s, the first 6 can consume the full 30s. The UI then publishes the nextOffset and stops, requiring a manual button to restart `LaunchedEffect` via `tmdbLookupEpoch`. This prevents a single user action from searching the rest of the already authorized sites, even when later sites have verified playback.

**Goal:** On opening a TMDB film without a verified NAS/cloud/CMS source, scan successive bounded pages automatically until first playable, all 45 have been tried, or an explicit overall time budget ends. Progress must be visible and truthful. Never select a title-only match without actual media-byte verification.

**Files:**
- `android-tv/app/src/test/java/tv/herrin/app/M472ApprovedSpiderBatchTest.kt`: RED 45-site no-match scenario and GREEN later site/early-exit/progress coverage.
- `android-tv/app/src/main/java/tv/herrin/app/HerrinApprovedSpiderBatch.kt`: larger finite total page and wall-clock cap, optional stop-on-first verified match, progress callback, preserve site/session constraints.
- `android-tv/app/src/main/java/tv/herrin/app/MainActivity.kt`: enable auto scan on no verified source, show `已检索 x/45` status between pages and correct exhausted message. Detail screen only; no accepted Home UI modifications.

## Task 1 — reproduce (TDD RED)
- [ ] Add a no-delay JVM fixture with **45** approved but dead sites. Assert a single `scanAcrossPages` checks all 45 and terminates with `nextOffset=0`, not `24` or `6`.
- [ ] Test-only commit, verify actual GitHub Actions Android Debug JUnit RED; do not accept test compilation failures as evidence.

## Task 2 — implement and guard
- [ ] Increase `maxPages` bound from 6 to 17 and default enough for 100 eligible sites, maintain `PAGE_SIZE=6`, existing 4.5s per-site timeout, one active Spider and verified URI requirements. Increase total budget to a finite cap sufficient for 45 * 4.5s plus page overhead.
- [ ] Add optional `stopAtFirstPlayable`, publish cumulative checked/eligible/offset progress after each page. In Main use first playable under the existing trusted JAR/root authorization; do not keep searching for speculative 4K before enabling playback.
- [ ] UI status while scanning `正在自动检索已授权影视点 x/45`; on total exhaustion say no verified source found rather than `点击重新检索可继续下一批`. If total deadline expires, keep the existing resumable offset and report it honestly.
- [ ] Add GREEN tests that later verified node is found automatically, stop on first verified, no out-of-scope site or endless offset cycles.
- [ ] Inspect current HEAD Android Debug unit/lint/assemble and TV API 30 167/167; capture test failure/no-skip proof and APK artifact, keep Draft PR #80 unmerged.
- [ ] Document that all 45 *verified failures* can still mean third-party Spider playerContent is incompatible; no fake playback or generic proxy bypass.
