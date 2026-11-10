# P0 未完成：真实播放修复续跑

基线HEAD：79999f3538b5adc5337efff15befdc187d520bb5。主开发分支 codex/herrin-global-search-20261107，Draft PR #84；当前专项临时分支 codex/herrin-p0-live-playback-20261110。

硬性标准：玩偶哥哥 REAL_PLAYBACK_PASS 或真实QR/cancel/Back已验且等待私人扫码的 AUTH_FLOW_PASS_PLAYBACK_PENDING（仍是阶段性）；奶酪必须 REAL_PLAYBACK_PASS。必须实际出帧、连续60秒、暂停恢复、Seek、同集重播/第2集/回第1集/返回详情再播。未达标不能宣布P0完成，不生成验收APK。不使用Superpowers，不合并main/master，不放宽媒体/本机归属/登录/DRM校验。

已验证且不要重跑：Android11 Google APIs x86_64 可通过标准包ABI选择执行ARM64和ARMv7 JNI及两个Spider初始化。实时节点：玩偶 / csp_WoGGGuard；奶酪 / csp_T4Guard。测试授权片目《小城良方》（2026），多集。奶酪搜索/身份/29集/playerContent通过；首集HLS_MANIFEST_REJECTED、本机HTTP502；重播及第2集本机归属检查失败。玩偶搜索空文本，首页无目录后宿主源描述被清空。

先修奶酪：拆分HLS头错误、回环引用重写拒绝、回调和I/O生命周期；再追踪FongMiSessionAuthorization具体Health失败，不允许跳过UNAUTHORIZED_LOCAL。后修玩偶：对照固定FongMi上游调用顺序，保留空首页下已成功初始化的执行会话，区分空结果/授权/接口错误/会话失效/无资源。

本轮初始修改：代理prefix读取异常时关闭流；新增固定HLS拒绝子码及Health细分；真实测试60秒门槛；单节点ARM64工作流，CI采集绿不再等同成功，真实验收JSON独立gate。仅构建用于安装测试的诊断包，不上传/发布验收APK。

精确续跑：

```sh
cd /workspace/aura-tv
git status --short
git log -1 --format='%H %s'
# 在已配置SDK/NDK/Gradle和ARM64或Google APIs转译设备的环境，实时奶酪：
LIVE_NODE=cheese ARM_PACKAGE=true LIVE_INSTALL_ABI=arm64-v8a LIVE_METHOD_METADATA=false LIVE_REQUIRE_ACCEPTANCE=true bash android-tv/scripts/run_live_nodes.sh
# 玩偶对应：LIVE_NODE=wanou，其余相同。
# GitHub专项只监听当前临时分支；普通修复提交跑奶酪，提交信息含[wanou]才选择玩偶。
# 现场数据：evidence/cheese.json 或 evidence/wanou.json；失败必须继续修复或明确BLOCKED并保留实证。
```

已有完整回归失败必须最终核查：TmdbBrowseRefreshTest.changedNetworkOrderPublishesOnlyAfterTheFocusedRailIsLeft，TmdbBrowseRefreshTest.kt:62，等待刷新缓存tmdb-movie-12超时10秒。历史统一CI：JVM702/702、Lint/Debug/API31与ABBA24/24通过；TV256/257。没有将其根因认定为flaky。阶段修复完成前不反复跑全量构建，最后统一完整CI并更新结果。

## 6edf804 targeted run (2026-11-10 09:21 UTC)

Run 42789190811 / job 129737962251 failed the real acceptance gate.
Cheese: init/search/year/29 episodes and playerContent succeeded. First media
read ended NETWORK_ERROR; Android ApplicationExitInfo reports native (JNI/so)
crash. Later URI health is CONNECT_REFUSED with current_site_matches=true,
not evidence of an unauthorized foreign URI. No decoded frame. Prior HLS 502
and this new native crash are separate observed failures; do not infer a fix.
Report: /workspace/reports/herrin-p0-20261110/6edf804-cheese.json.
Next run adds only safe native crash module/signal classification and strict
five-operation/60-second acceptance; never uploads raw provider crash logs.

## dab76a5 true Cheese retest / 42790701662 / 129743068737

No native crash on this run. Callback is exactly Object[3]: Integer200, HLS
MIME, ByteArrayInputStream. Both default/extended attempts reject the HLS
**header**, HLS_HEADER_INVALID; not a nested-reference rejection. The extended
probe reaches HOST localhost502 at2230ms; current session health remains READY.
No frame/Seek. First-stage stop now works. Tests/build passed; real acceptance
correctly failed. Safe native crash summary is empty.

57e8873 Wanou run42791018232 /129745130357 stopped before emulator because
new unit test missed Assert.fail import. Corrected in25607c3; that run is not
a provider verdict. 57e8873 actual runtime change: always call home(true) then
homeVideoContent, and let an empty catalog publish the initialized session.
Search empty body is SOURCE_EMPTY_RESPONSE, not JSONObject crash or no resources.

6ba4216 real Cheese next: remove probe-injected Range for HLS/native proxies to
match FongMi manifest requests; bounded byte reads and all auth checks retained.
Adds fixed XML/gzip/URL/base64/access-denied body classifications. No timeouts
increased. Both node runs pending; next inspect runs42791806426 (Wanou25607c3)
and42791877350 (Cheese6ba4216). Strict acceptance workflow stays red on failure.

## 6ba4216 resumed evidence and next repair

Cheese run42791877350 /job129747089719: same real29 episodes, playerContent,
current proxy READY. HLS body is HLS_BODY_URL (begins with HTTP(S)), not an
actual manifest. Original callback200/HLS/ByteArrayInputStream is rejected by
host as502. Range removal did not solve it. No frame. One safe crash-buffer
entry SIGILL with libc/libndk_translation modules was also recorded; not proof
that every proxy error is a native crash.

Wanou run42791806426 /job129746849269: init6413ms, home0, active_site_after_home
true, search9ms worker/101ms stage gives SOURCE_EMPTY_RESPONSE; no QR or native
crash. Empty home session loss fixed; original node root cause still unresolved.

Next repair resolves ONLY a validated single URL carried by this approved HLS
callback, inside the existing native proxy. Public DNS, bounded response, no
credentials forwarded, same-origin redirect and HLS child/key references only.
Reject HTML/invalid media, private destinations, foreign redirects, credential
origin ambiguity. Does not invent a manifest/video or bypass login. Real node
retest must determine whether remote content is playable. Targeted unit tests
are protocol fixtures only and cannot establish provider playback success.

## Resume through 7376077 (2026-11-10)

- e235e3b resolver production Kotlin compiled, but its new fixture used Java-SAM
  syntax for Kotlin OkHttp Dns; fixed in77212ee. Target unit suite/build then
  passed; real Cheese run42808187811/job129802639382 failed as it should.
- Cheese callback URL passed strict single-reference validation. Fetching its
  original media reference reached real **upstream HTTP403**, twice; proxy
  was READY. The new resolver correctly did not accept/transform the403 body
  into media. Still no decoded frame. Saved77212ee-cheese.json locally.
- 0b2769f adds scheme and fixed403 reason markers (HTTPS-required, domain-policy,
  standard expired/mismatched signature, access denied, HTML/JSON/unspecified),
  no raw content or credentials. Report flags blocked upstream401/403 and
  acceptance stays red. Do not bypass this denial or fake successful playback.
- 3d6ef25 restores pinned SDK two-argument -> three-argument page1 delegation.
  Target unit suite/build passed. Real Wanou run42808331029/job129803115440
  STILL EMPTY_BODY, both overload owners BaseSpiderGuard, worker search8ms;
  active_site_after_home=true, home0, no QR. SDK compatibility fixed but not
  this node's root cause. Saved3d6ef25-wanou.json.
- 284cae6 adds passive fixed SDK HTTP status/DNS/TLS/timeouts and Activity/auth
  prerequisite observation with source-owned lifetime (no request data). It
  also observes a QR even if the Spider operation already returned. Real Wanou
  run pending. SDK-only network evidence is not native network visibility.
- Original TMDB refresh test at8413039 passed isolated (run42807989220,
  job129801983177). Counters entered12/returned10/errors2 at teardown,
  changed-response1, cached_changed=true. This does NOT fix the full-suite
  prior regression or establish flakiness.
- 7376077 runs opt-in16second hold BEFORE fixture release to test whether its
  own15second await expires prematurely. Original15/10/5second UI assertions
  are untouched. Expected reproduction may be red; inspect errors_before_release.

PR84 still draft/unmerged, actual PR head79999f3; repairs remain on requested
codex/herrin-p0-live-playback-20261110 until phase verification. No full CI rerun
or acceptance APK has been generated for these repairs. Pending: inspect new
runs, resolve real source blockers legitimately; fix/retest TMDB after concrete
reproduction; then same-HEAD full JVM/Lint/Debug/TV/ABBA; APK only after real+CI.
