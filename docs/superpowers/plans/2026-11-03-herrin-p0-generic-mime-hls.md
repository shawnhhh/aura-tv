# Herrin P0: Correct an HLS Manifest Advertised With a Generic Proxy MIME

> Follow Superpowers systematic-debugging, writing-plans, test-driven-development, executing-plans, verification-before-completion.

**Goal:** Make a recognized HLS manifest from an already-approved CatVod Java Spider playable when the plugin incorrectly labels it `application/octet-stream` or `text/plain`, without ever rewriting TS/MP4/MKV bytes.

**Evidence from source:** `SpiderLoopbackProxy.serve()` only invokes `HerrinSpiderHlsProxyRewrite.rewrite()` when `mime.lowercase().contains("mpegurl")`. FongMi-compatible Java Spider proxy results may contain an actual `#EXTM3U` manifest and nested `proxy://` segment/key aliases with a generic MIME; they bypass the existing authenticated-session rebasing, leaving unusable nested URLs. The existing HLS rewrite tests cover only correctly labelled manifests.

**Scope:** Draft PR #80 branch `codex/m471-tmdb-personal-to-tvbox-playback-20261102`, no main merge, no Home UI edits, no new Spider operations, no unauthorized requests, never log tokens/URLs.

## Task 1 — RED

Files: `android-tv/app/src/test/java/tv/herrin/app/M476SpiderHlsRewriteTest.kt`.

- [ ] Add local loopback HTTP regression: an approved static handler returns HTTP 200 generic MIME with `#EXTM3U` and nested `proxy://do=jar&siteKey=approved`; assert the response has only session-bound, active-port URLs (not raw `proxy://`), and canonical HLS MIME. Test binary `application/octet-stream` remains byte-for-byte unchanged.
- [ ] Commit only tests; verify an exact RED failure from the new test in HERRIN Android Debug CI, not compile/environment failure.

## Task 2 — GREEN

Files: `android-tv/app/src/main/java/tv/herrin/app/SpiderLoopbackProxy.kt`.

- [ ] For HTTP 200 GET without Range or Content-Encoding, peek only the fixed `#EXTM3U` signature using a tiny pushback buffer, never buffering video bytes.
- [ ] If the existing MIME says HLS OR the signature is verified, invoke the current <=256KiB HLS rebinder, preserving its rejects for unapproved nested routes; use canonical manifest MIME for newly recognized generic-MIME HLS.
- [ ] Ensure non-HLS and 206 byte streams are not modified/truncated. Keep the same response/session/worker limits.

## Task 3 — Verify

- [ ] Full current-HEAD unit/lint/assemble Debug and TV API 30 emulator 167/167 with zero failures/skips, inspect original logs.
- [ ] PR Draft/no merge, source-level diff only test/proxy/plan and report physical TCL playback still blocked pending real third-party logs and first rendered frames.
