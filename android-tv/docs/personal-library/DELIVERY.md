# SMB / WebDAV personal library — implementation and verification record

Branch: `codex/herrin-p0-live-playback-20261110`; existing PR #85 remains Draft. Base inspected before edits: `f4e3c092b53efaf0b61161fd692d0e3b8ac22177`. No merge; no second player, cloud gateway, platform-specific cloud login or LAN discovery.

## Implemented

- Removed independent CLOUD routing, native-cloud placeholder tabs, bridge forms and `/cloud`/`cloud/test`. One-time retired cloud preference, encrypted payload and AndroidKeyStore alias cleanup preserves NAS/SMB secrets. TVBox/CatVod/Spider authorization is retained.
- UUID server connections, independently encrypted server/account records, manual HTTP/HTTPS/SMB form fields, default ports 443/80/445, optional domain/SMB3 encryption. Authority/account changes cannot inherit the previous password. Candidate validation precedes edits. Multiple folders reuse the same connection credentials.
- Legacy primary DAV, primary SMB and additional DAV settings migrate without deleting originals; their pending first index runs serially in the background on startup and notifies playback lookup after import; disabled additional connections remain disabled.
- Folder import/type/enable controls, confirmed removal, parent/child overlap rejection, explicit refresh and cancellation. Deleting configuration never issues remote file deletion.
- SQLite file identity, relative path, name, size (64 bit), modification marker, extension, category, parsed title/year/season/episode, TMDB identity/confidence, manual override, availability and scan timestamp. No passwords, headers or signed playback URLs in this database. Connection root paths are retained in the encrypted connection record.
- Explicit serial scanner, incremental unchanged-file writes, persistent partial progress, bounded directory traversal. Only a fully successful directory scan marks missing files; failure, cancellation or limits retain old index/history. Restart/refresh re-enumerates and reuses persisted files; it does not persist a network traversal cursor.
- Index-first personal playback search; existing source router performs actual readability verification before exposing an offer. Existing SMB controlled Range service and FongMi/Media3 path remain in use. Indexed raw videos can be selected from TV personal settings. Same-film versions use existing personal playback lines; TV library groups versions within the current page.
- Serial TMDB search using existing client/identity matching, conservative unambiguous matches only, official poster/overview projection, retained raw files on failure, explicit TV/phone TMDB search and manual corrections (identity/year/poster/synopsis) protected from subsequent automatic updates. Original TMDB title aliases match localized metadata; explicit Other Video folders skip automatic film identification.
- HTTPS paired phone center manages connections/folders, status, scan progress and matching. HTTP DAV does not change management TLS trust/session/origin checks. Redirects disabled on personal authenticated requests. No global TLS bypass.
- Bounded TV/phone library and folder-browser paging/filtering. One reusable SMB/DAV transport per scan; directory handles, HTTP pools and per-scan dispatchers close on completion/cancellation. Other catalog navigation and accepted visual surfaces preserved.

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

New production modules: `PersonalStoragePolicy.kt`, `PersonalConnections.kt`, `PersonalMediaIndex.kt`, `PersonalRemoteFiles.kt`, `PersonalLibrary.kt`, `PersonalLibraryApi.kt`, `PersonalLibrarySettings.kt`, `PersonalLibraryStartupImport.kt`, `PersonalLibraryIndexChanges.kt`, `PersonalMediaMatching.kt`.
New tests: `PersonalStoragePolicyTest.kt`, `PersonalWebDavTransportTest.kt`, `PersonalMediaIndexTest.kt`, `PersonalConnectionsTest.kt`, `PersonalLibraryImportTest.kt`, `PersonalMigrationTest.kt`, `PersonalIndexRevisionTest.kt`, `PersonalMediaMatchingTest.kt`, `PersonalRemoteSessionTest.kt`.
Updated existing settings, preferences/vault, router/discovery, DAV source, playlist labels, LAN server, personal MainActivity integration, phone assets/browser regression and affected routing/TV tests. Updated existing CI callers to serialize P0 jobs and bind new diagnostic APK to source SHA plus SHA-256.
No entire source files deleted; independent cloud implementation removed from shared files. Root GitHub Pages assets unchanged.

## Final-source verification

Code/APK source SHA: `082f1fa770e6c23575ff3aca9ccd972404071ec2`.
Serial run: https://github.com/shawnhhh/aura-tv/actions/runs/44251242702 .

- JVM: **753 tests, 0 failures/errors/skips**, including 12 SMB policy/protocol categorized checks with the controlled Samba SMB2/SMB3 fixture. Android Lint and Debug compilation/assembly passed.
- TV attempt 1 stopped at 72/277 tests: `HerrinUiSmokeTest.searchFilterDpadDownReachesSuggestionsAndResultAndUpReturns` encountered `NoSuchElementException` in Compose `DepthSortedSet.pop` / `MeasureAndLayoutDelegate`, followed by process death. No application frame in that crash stack. Retained the failure, unchanged assertions/timeouts and requested exactly one failed-job rerun; successful JVM/build job is not repeated. Attempt 2 passed **277/277 (0 failures/errors/skips)**; independently verified the downloaded JUnit XML in artifact `14404834646`. This pass includes cloud cleanup, encrypted isolated credentials, legacy migration, HTTP import/index/offline/deletion, raw/SMB/WebDAV decoded-frame and seek regressions. The cause of the internal Compose queue inconsistency is not established; the unchanged rerun pass does not prove that intermittent defect fixed. Android12 startup job `134634976961` passed: 33 sampled frames, interactive home reached, `physical_TCL=false`; evidence artifact `14406156996`. Same-device TMDB ABBA job `134637387890` also passed; artifact `14405719950` locally downloaded and `summary.json` candidate verified as `082f1fa7`. Six tests per A1/B1/B2/A2 leg, one API30 x86 SwiftShader AVD, no physical TCL. Candidate mean first-draw delta +5.5 ms, home delta -432 ms and 11-transition cached-navigation delta +132.5 ms versus baseline `599d58d`; this is not evidence of universal performance improvement or physical remote latency. All four substantive serial jobs ended successfully; duplicate nested startup job was intentionally skipped.
- TV evidence includes original MP4/HLS compositor frames, over 60 seconds continuous playback per format, pause/resume, seek with new frames and release on Back. `SmbRealPlaybackTest` exercises original MP4/MKV/TS/M2TS over controlled Samba; `M471WebDavRealPlaybackTest` exercises DAV Range/read/decode. These are controlled fixture tests, not physical NAS/TCL acceptance.
- Previous run `44245126111` reached all 277 TV tests with one failure: the new multi-directory fixture still called `folders(connection).single()` after creating a second folder. Fixed only the fixture waiter to select the expected folder UUID; added assertions that the Other folder survives offline/movie refreshes. No production code or timeout changed in `082f1fa7`.
- Earlier full baseline `5f22b267`: JVM 746/746, TV 275/275, Android12 startup and same-device TMDB ABBA passed in run `44235878247`. Baseline evidence is not a final-source pass. Superseded/cancelled attempts are not counted as passes.

Latest newly built Debug diagnostic APK: https://github.com/shawnhhh/aura-tv/actions/runs/44251242702/artifacts/14404725460 .
APK size: **27,006,078 bytes**. APK SHA-256: `4031eff64694afd66de78aed0a698c58c243c12c276b6b3a7be50de4f51c043b`.
Downloaded the artifact, verified its `SOURCE_COMMIT.txt` equals the source SHA above, and independently hashed the APK against `SHA256SUMS.txt`. Verified the startup-import/matching/index-change/session classes are present in DEX. This is a newly built diagnostic package; it does not establish real-device acceptance.

The complete path/status manifest from inspected base `f4e3c09` is [changed-files.txt](changed-files.txt). No root Pages assets changed; no entire production source file was deleted. Standalone cloud code was removed from shared files. The legacy acceptance-report sentence about no P0 APK upload predates the explicit diagnostic-artifact upload; use the actual artifact metadata and source/hash binding.

Final documentation commit changes only this ledger and its path manifest. The APK and all executable-code test results remain bound to source `082f1fa7`; documentation changes do not substitute a different APK.


## 2026-11-20 H 系列续开发

本轮修复 TV SMB/WebDAV 协议表单与请求 scope 的生命周期隔离，协议按钮 DOWN 到名称输入，完整 URL 输入上限，取消传播与轮询失败收尾；去除连接/文件夹技术状态正文。手机退出即时失效会话，模块离开再返回仍丢弃旧请求反馈，保留已提交密码清理。完整最新提交与测试、环境阻塞见 [H-001～H-009 台账](../detail-player/H-001-H-009-20261120.md)。本节不覆盖上方历史测试绑定，也不将历史 APK 当成本轮验收 APK。


## 2026-11-20 第二轮：真实 SMB fixture 与表单竞态

已使用工作区解包的 Samba 4.22.11 恢复认证、只读、签名/加密的 loopback 1445/1446 服务，无系统安装或认证放宽。先跑 SmbProtocolIntegrationTest 6/6，再全量 JVM 787/787；CI 也增加专项先行门槛。本轮 `center.js` 增加保存表单 revision，迟到结果不能向已编辑的新表单写 ID；TV 对目录与表单作同等保护，按协议过滤已导入文件夹。原 HTTP/HTTPS URL、80/443/自定义端口和基目录尾斜杠保持，真实阿里云盘 WebDAV 无账号/环境仍未验收。当前源码绑定的诊断包及最终 CI 状态见 [最新 H 台账](../detail-player/H-001-H-009-20261120.md)，不能复用上方历史 APK 当最新包。


中断续跑补充：代码 de2be68e 将合法空 CMS list 与配置/HTTP 失败区分；内联节点使用现有授权激活链路，旧请求不能覆盖新选择。2b473860 为当前测试源码，串行 CI 44705015249 正在执行。f1abdd0f 的受控 TV SMB3 实际完成加密、解码帧和 Seek，但当次 TV 全量仍有 20 项失败；不能把其中协议成功等同整体验收。最新完整结果及诊断 APK 绑定以 H 台账开头为准。


当前续跑验证：2b473860 完整 JVM 已增至 788/788，20 项原失败 TV 专项 19/20；手机 HTTPS 提交 HTTP 仓库并刷新 TV、错误配置保留已生效配置、空 Spider 首页等受控场景通过。H-004 余下 fixture 修正及统一旧节点入口/去分类技术文字已进入 ba5cc55f 串行 CI，尚未记为全量通过。物理 Safari/TCL/阿里云盘结果仍为空。
