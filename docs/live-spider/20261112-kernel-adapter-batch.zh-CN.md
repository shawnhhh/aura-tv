# 2026-11-12 Herrin P0 original-core adapter batch

Branch: `codex/herrin-p0-live-playback-20261110`, baseline `46b8f8c7`.
Keep PR #84 Draft; no main merge and no accepted TV UI changes.

## Actual source changes

- `b99ac601`: warehouse load can only report READY after an explicitly
  approved site appears in the original VodConfig. Navigating to a new movie
  retires all opaque episode handles from the previous detail; catalog movie
  handles remain valid for future search/detail.
- `2b8ba2dc`: the original VOD controller emits its detail observer before
  completing its detail future. Reuse that already-published Herrin projection,
  rather than clearing and reconstructing netdisk episode selections twice.
- `8bfcafe0`: empty original VOD details invalidate previously stored episode
  handles instead of leaving stale lines addressable.
- This checkpoint switches the lightweight host CI to batch-final validation
  (manual dispatch or workflow-change push), and adds the independent
  `kernel-contract:check` gate to the same host compile run.

## What the next single CI run proves

Only the stable Android host, shared Java cutover contract, and their
compile/tests. The isolated original `fongmi-kernel/core` and `adapter`
**cannot yet be compiled** because corresponding custom Media3,
MPV/FFmpeg dependencies remain missing. Do not treat this run as a real FongMi
player build, a Media3 first-frame test, or `小城良方` verification.

The next functional milestone after collecting genuine licensed compatible
media inputs is an independent original-core Android build and explicit
stable-to-original host classpath cutover. Only after that should deprecated
Spider/Proxy/HLS playback paths be deleted and complete TV/ARM node
verification be performed. No release APK is accepted at this checkpoint.
