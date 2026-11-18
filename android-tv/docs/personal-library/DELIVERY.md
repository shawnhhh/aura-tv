# SMB / WebDAV personal library — implementation and verification record

Branch: `codex/herrin-p0-live-playback-20261110`; existing PR #85 remains Draft. Base inspected before edits: `f4e3c092b53efaf0b61161fd692d0e3b8ac22177`. No merge; no second player, cloud gateway, platform-specific cloud login or LAN discovery.

## Implemented

- Removed independent CLOUD routing, native-cloud placeholder tabs, bridge forms and `/cloud`/`cloud/test`. One-time retired cloud preference, encrypted payload and AndroidKeyStore alias cleanup preserves NAS/SMB secrets. TVBox/CatVod/Spider authorization is retained.
- UUID server connections, independently encrypted server/account records, manual HTTP/HTTPS/SMB form fields, default ports 443/80/445, optional domain/SMB3 encryption. Authority/account changes cannot inherit the previous password. Candidate validation precedes edits. Multiple folders reuse the same connection credentials.
- Legacy primary DAV, primary SMB and additional DAV settings migrate without deleting originals; their pending first index runs serially in the background on startup and notifies playback lookup after import; disabled additional connections remain disabled.
- Folder import/type/enable controls, confirmed removal, parent/child overlap rejection, explicit refresh and cancellation. Deleting configuration never issues remote file deletion.
- SQLite file identity, relative path, name, size (64 bit), modification marker, extension, category, parsed title/year/season/episode, TMDB identity/confidence, manual override, availability and scan timestamp. No passwords, headers or signed playback URLs in this database.
- Explicit serial scanner, incremental unchanged-file writes, persistent partial progress, bounded directory traversal. Only a fully successful directory scan marks missing files; failure, cancellation or limits retain old index/history. Restart/refresh re-enumerates and reuses persisted files; it does not persist a network traversal cursor.
- Index-first personal playback search; existing source router performs actual readability verification before exposing an offer. Existing SMB controlled Range service and FongMi/Media3 path remain in use. Indexed raw videos can be selected from TV personal settings. Same-film versions use existing personal playback lines; TV library groups versions within the current page.
- Serial TMDB search using existing client/identity matching, conservative unambiguous matches only, official poster/overview projection, retained raw files on failure, explicit TV/phone TMDB search and manual corrections protected from subsequent automatic updates.
- HTTPS paired phone center manages connections/folders, status, scan progress and matching. HTTP DAV does not change management TLS trust/session/origin checks. Redirects disabled on personal authenticated requests. No global TLS bypass.
- Bounded TV/phone library paging/filtering. Other catalog navigation and accepted visual surfaces preserved.

## Limits and outstanding acceptance

This is an incremental implementation, not full acceptance of all requested stages:

- SMB server-root share enumeration is not implemented. Enter a share name in the path field, then browse its descendants; multiple shares reuse one connection. No network discovery is performed.
- DAV: maximum 4 MiB and 10,000 entries per directory; SMB: 10,000 entries per directory. Import: depth 16, 10,000 directories and 100,000 media files; one active scan. Exceeding limits fails visibly and preserves prior entries, rather than treating truncated output as deletion.
- Automatic TMDB enrichment is capped at 200 unmatched records per refresh; repeated ambiguous/nonmatching records can delay later records. Unmatched videos remain visible/playable and can be manually matched.
- Cancellation/restart preserves index but resumes by re-enumeration; there is no persisted directory-queue checkpoint. Versions crossing page boundaries are not yet grouped in the library list.
- Readability checks and decoded-frame tests on controlled fixtures do not establish real NAS, SMB3 encrypted throughput, TCL remote ergonomics, 4K, Dolby Vision or HDR compatibility. Real user-owned SMB/DAV servers, phone Safari and TCL device are pending verification.

## Verification ledger

- Local `node --check .../center.js`: passed.
- Local `scripts/check_static.py`: passed (XML/manifest/wrapper, pinned FongMi boundary and Sardine sources).
- Local Python playback-boundary tests: 4 passed.
- Local Chromium mobile frontend using mocked API: passed; certificate acknowledgement, common pairing session, HTTP/default/custom ports, encrypted-SMB form fields, secret clearing, cloud removal, source/batch regression, session end. Not an Android-server or real Safari test.
- Local Gradle attempt: blocked before compilation (`Operation not permitted` downloading Gradle); explicit network retry: `UnknownHostException: services.gradle.org`. No local Android SDK/device.
- First CI `44231044185` on `9d5cee92`: failed Kotlin compile due to one remaining `cloudUrl` cache-key reference. Fixed in `2e4e6fef`. No test assertions removed to mask failure.
- Existing baseline ABBA failure `44221599250`: Maven `jsr305:3.0.2` HTTP 429 before device testing; unrelated baseline remains explicitly recorded.
- Subsequent unified CI results and latest diagnostic APK are recorded in the PR after completion. Never substitute an earlier artifact for the final source.

## Changed files

New production modules: `PersonalStoragePolicy.kt`, `PersonalConnections.kt`, `PersonalMediaIndex.kt`, `PersonalRemoteFiles.kt`, `PersonalLibrary.kt`, `PersonalLibraryApi.kt`, `PersonalLibrarySettings.kt`, `PersonalLibraryStartupImport.kt`.
New tests: `PersonalStoragePolicyTest.kt`, `PersonalWebDavTransportTest.kt`, `PersonalMediaIndexTest.kt`, `PersonalConnectionsTest.kt`, `PersonalLibraryImportTest.kt`.
Updated existing settings, preferences/vault, router/discovery, DAV source, playlist labels, LAN server, personal MainActivity integration, phone assets/browser regression and affected routing/TV tests. Updated existing CI callers to serialize P0 jobs and bind new diagnostic APK to source SHA plus SHA-256.
No entire source files deleted; independent cloud implementation removed from shared files. Root GitHub Pages assets unchanged.
