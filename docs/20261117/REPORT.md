# HERRIN Android TV 2026-11-17 开发与测试记录

基线：`64bba93c3ac3fecd8e94e65045dcf8c69d7dadb0`。开发分支：`codex/herrin-p0-live-playback-20261110`。
既有 P0 成果已检查并保留；本地最初仅有早期 master 检出，无用户未提交修改；通过显式 fetch 获取最新分支。
[Draft PR #85](https://github.com/shawnhhh/aura-tv/pull/85) 保持 Draft，不合并主分支。
本轮使用原生 Codex 串行开发，没有使用 Superpowers 或并行代理。

## 实现

- V13：中心向外连续显影 2250ms；1200ms 开始白色呼吸，周期 2400ms；固定字形/尺寸，移除字外光晕。首页可用且显影结束即可淡出；慢网不阻止导航骨架，未就绪继续呼吸。保留减少动态效果和遥控器跳过。
- 在显影期间构建首页；启动配置、目录迁移和文本/诊断准备并发执行。只恢复首页的首批栏目缓存，分类切换再恢复对应缓存；Top250 缓存不再是退出动画的门槛。保留已有系统 Splash 空图标及首帧交接和真实阶段日志。
- 普通热门/高分/国内列表不再逐片展开完整详情。首页热门使用官方趋势，分类热门优先 Discover；Top250 与全局搜索代码保持原有语义。缓存先展示、独立刷新；相同 HTTP 请求共享互斥与短缓存，网络并发上限 3。
- 最新上线独立筛选：电影 Discover 发行类型 4/5/6、发行地区和 UTC 日期窗口；有界验证使用 movie/release_dates；剧集二次验证真实已播集与状态，忽略下一集预计日期。数字日期优先，Physical/TV 补充；缓存保留发行国家、类型和日期。
- 地区通过 with_origin_country 查询，与发行市场独立。美国/中华地区、日本/中华地区、中华地区/韩国分别分池，目标 10+10，短缺补足；纪录片全球。查询约束单独缓存，不伪称所有实际制作国，不按对白语言推断。
- 最新动漫覆盖动画电影与剧集；综艺允许连载；最新列表按已验证发行/播出日期排序，跨页去重，保留有效余量。每批验证最多 40 个候选、最多 5 次补页、总验证窗口 15 秒；证据先到先显示，失败保留缓存。首批目标 20，候选不足或超时可以少于 20，不补假数据。
- 分类背景过渡按当前分类隔离，避免上一分类图层残留；保留 original 背景策略及现有海报加载/解码缓存实现。
- 仅更新首页齿轮：圆润连续白色轮廓，默认透明，聚焦半透明及轻微缩放；顶部横向导航、顺序、位置与背景配色不变。
- 设置中心左侧四模块，右侧配置。个人媒体库五协议横向一排，原 WebDAV 两个持久化槽保留；SMB 复用真实加密配置存储；百度/阿里/夸克为独立原生入口并明确标注待接入。全局扫码复用真实服务；模块内重复手机入口已移除。
- 不显示通用设置/高级工具独立导航，底层诊断仍保留。Debug 包可显式 `am start -n tv.herrin.app/.MainActivity -a tv.herrin.app.DIAGNOSTICS` 打开开发诊断，不向 Release 包开放该动作。

发行证据只是元数据条件，未标记“已验证可播放”；未在首页逐片搜索影视点，未使用 watch_provider 代替用户权限。播放仍由既有个人库→所选影视点→其他授权影视点流程决定。未重写 FongMi 或跨节点搜索。

## 检查点

```text
c944031e perf(startup): prepare home during 2250ms V13 reveal without halo
29e37501 perf(tmdb): use list metadata and bound latest release market verification
a374709e feat(settings): add four-module sidebar and unified personal media protocols
d71d6de8 fix(p0): preserve market evidence cache and module focus boundaries; add release matrix tests
44a82539 fix(tmdb): retain domestic rail and advance verified pagination cursors
1534cd3f test(p0): cover metadata request dedup and native settings focus targets
beb72c24 perf(p0): initialize startup prerequisites concurrently and discover category rails
9988b862 test(settings): adapt remote regressions and retain debug-only diagnostic access
c9a1c9e1 test(tmdb): target discover loading paths and avoid obsolete proof timing claims
02178d1c fix(browse): scope hero transition to current category to drop previous artwork
ced3b37d fix(tmdb): carry verified surplus across latest page boundaries
7ad7cd96 fix(test): opt in to Compose remote key input API
9978e867 test(tmdb): retain movie identity for latest animated film coverage
419f1d6a test(browse): verify lazy first-visit cache without previous-category artwork
18cd46e1 test(latest): verify released anime movies and aired series in one rail
```

## 测试与环境

- `git diff --check`：通过。
- `android-tv/scripts/check_static.py`：通过；XML/TV manifest/wrapper/native-only、43 个固定 FongMi 文件、90 个 Sardine 文件及播放边界未变。
- Python：android-tv/scripts 29 项、native kit 验证器自身回归 14 项、FongMi reference 回归 10 项通过。测试中打印的 BLOCKED/模拟证据是夹具输出，不是真实节点验收结论。
- 新增 JVM 覆盖发行类型、未来/缺失日期、美国先发行、多市场日期、已播及未来下一集、地区均衡/补足、稳定 ID 去重与排序；增加相同列表请求去重测试。
- 设备测试增加四模块/五协议原生焦点目标与横向布局检查，并适配原有扫码、文本编辑、源导入及返回回归。
- 本地原环境没有 SDK、Gradle 缓存，Java21 缺失 jlink。已在工作区安装官方 Gradle8.9、Android35 SDK、完整 Temurin17；使用环境代理与 CA 信任库，不关闭 TLS 验证。
- 本地 ADB 列表为空，且无 /dev/kvm；未声称 TCL 或本地模拟器验收通过。
- 最终构建、JVM、lint、AndroidTest 编译和 CI 状态见下方最终结果。

## 性能对比边界

| 指标 | 修改前 | 修改后 |
|---|---|---|
| 动画最早正常交接门槛（代码配置，非实测） | 4200ms + 340ms 淡出 | 2250ms + 340ms 淡出 |
| 冷启动首页缓存恢复范围（代码路径） | 全部分类 22 个首批栏目 | 首页 3 个，其他按分类读取 |
| 普通列表逐片详情请求（代码路径） | 每栏最多 24 个候选并可补充 | 0；最新发行验证单独有界执行 |
| 动画首次可见实测 | 未测 | 未测 |
| 冷启动首页可交互实测 | 未测 | 未测 |
| 首页首屏/分类切换/首批耗时 | 未测 | 未测 |
| 真实 TMDB 请求数/重复数 | 未测 | 未测 |
| 图片缓存命中率/卡顿/崩溃实测 | 未测 | 未测 |

以上静态配置不能当作真实冷启动提速结论。已有 StartupTiming 和 CI ABBA 可采集实际数据，但不能复用昨日数据冒充本次结果。没有用户 TMDB 凭证或合法真实源验收输入，本轮未做真实内容上线数量、真实片源和授权验证。

## 未完成与限制

- 真实 TMDB/慢网和 TCL 同设备前后性能数据未取得；首批 20 项与地区 50:50 是有界候选内目标，不保证任何时间点全球内容都足量。
- TMDB Discover 电影使用官方支持的 primary_release_date 服务端排序；最终展示使用二次验证得到的数字发行日期排序。这是有界候选排序，不是穷举全球发行史；较早院线、近期数字发行的作品是否进入候选仍受 API 分页和预算影响。
- 网盘正式原生 API 授权仍待接入，未伪造成功。
- 需要等待或核验最终 CI 与模拟器报告，不能把编译成功等同于遥控器及真实设备验收。
- 无额度查询能力，未估算剩余额度。

## 修改文件

- `android-tv/app/src/androidTest/java/tv/herrin/app/EmptySpiderHomeSelectionTest.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/HerrinSplashTest.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/HerrinUiSmokeTest.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/M449SettingsNoIdleBurstTest.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/P0CachedHomeHeroTest.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/P0ExitAndEntryTest.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/SettingsModulesTest.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/TmdbBrowseFixture.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/TmdbBrowseLoadingNavigationTest.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/TmdbBrowsePaginationTest.kt`
- `android-tv/app/src/main/java/tv/herrin/app/HerrinWordmarkSplash.kt`
- `android-tv/app/src/main/java/tv/herrin/app/MainActivity.kt`
- `android-tv/app/src/main/java/tv/herrin/app/SettingsModules.kt`
- `android-tv/app/src/main/java/tv/herrin/app/StartupPresentation.kt`
- `android-tv/app/src/main/java/tv/herrin/app/TmdbBrowsePolicy.kt`
- `android-tv/app/src/main/java/tv/herrin/app/TmdbBrowseRepository.kt`
- `android-tv/app/src/main/java/tv/herrin/app/TmdbBrowseState.kt`
- `android-tv/app/src/main/java/tv/herrin/app/WordmarkRenderer.kt`
- `android-tv/app/src/test/java/tv/herrin/app/LatestReleasePolicyTest.kt`
- `android-tv/app/src/test/java/tv/herrin/app/M439LaunchMotionPolicyTest.kt`
- `android-tv/app/src/test/java/tv/herrin/app/TmdbBrowseFixture.kt`
- `android-tv/app/src/test/java/tv/herrin/app/TmdbBrowseRepositoryTest.kt`
- `android-tv/app/src/test/java/tv/herrin/app/TmdbControlledNetworkTest.kt`
- `android-tv/app/src/test/java/tv/herrin/app/WordmarkMotionTest.kt`
- `docs/20261117/REPORT.md`
- `docs/20261117/ci-status.json`
- `docs/20261117/apk-manifest.json`
- `docs/20261117/jvm-first-run.json`
- `docs/20261117/jvm-targeted-recheck.json`
- `docs/20261117/latest-policy-recheck.json`
- `docs/20261117/tmdb-controlled-http.json`

## 最终本地结果与产物

- 主代码 Debug 构建成功，AndroidTest APK 编译成功；本地签名校验通过。
- Lint：通过，0 错误、45 警告（`android-tv/app/build/reports/lint-results-debug.html`）。
- 首次全量 JVM：738 项、6 失败、0 错误/跳过。5 个失败来自尚未启动 Samba 的协议用例；1 个既有节点恢复测试在与 lint 同时执行时得到 TIMEOUT，预期 NO_MATCH。保留原始 [汇总](jvm-first-run.json)，未修改搜索/播放器逻辑或放宽时限。
- 真实 Samba 服务补齐后，SMB 协议 + 节点恢复两个类定向复验 11/11 通过，0 失败/错误/跳过；[汇总](jvm-targeted-recheck.json)。夹具使用原始合成媒体，并非真实用户网盘或播放授权验收。
- 最新发行策略追加复验 5/5 通过，包含已数字发行的动漫电影与已播剧集同栏，电影保留 movie 身份；[汇总](latest-policy-recheck.json)。
- 30ms HTTP 响应头延迟的本地回环控制实验，10 样本：元数据预览 P50/P95=74/76ms，含列表补充完成 P50/P95=223/228ms。[原始样本](tmdb-controlled-http.json)。无优化前同条件样本，不能解释为真实 TMDB 或 TCL 提速。
- [远端 Debug job](https://github.com/shawnhhh/aura-tv/actions/runs/44061553509/job/133964588804)：`7ad7cd96` 的 JVM 738/738、lint/Debug 成功。此后主代码无变更，新增提交只调整/补充测试。该工作流后续 Android12 启动 job 因新提交而取消，不能将整个工作流记为成功。
- `7ad7cd96` 的模拟器前置回归 18/19；失败是首次分类访问仍要求启动预载全部缓存的旧断言。已在 `419f1d6a` 按本轮按需缓存规范适配：首次有界等待本地缓存、立即清除旧分类背景、重复访问保持即时断言。此修正不代表新设备复验已经通过。
- 最新模拟器/ABBA/启动结果仍待核验，见 CI 状态记录；本轮尚未宣布全部验收通过。

应用 APK：`android-tv/app/build/outputs/apk/debug/app-debug.apk`，28,842,490 字节，SHA256 `48f3b93c222b8281fbb7080593f0c065554e782cadafab8f0d284a57512b8cd9`。
这是内部 Debug 诊断产物，未经过 TCL 实机/真实节点验收；不是正式稳定版。
AndroidTest APK 信息及源码对应关系见 [产物清单](apk-manifest.json)。

未完成事项仍是最新完整模拟器回归、同设备前后冷启动/分类性能和实际 TMDB 首批数据验收；不能将已通过的元数据筛选测试解释为实际片源可播放。

## CI 状态与继续验证

状态快照见 [ci-status.json](ci-status.json)（含查询时间和确切 SHA）。截至快照，`419f1d6a` 的 Debug、模拟器和 TMDB ABBA 都在运行；最终报告/追加测试推送后应以该分支最新代码检查为准。没有把进行中或被取消的运行计为通过。

代码检查点：`18cd46e1`。本轮最终主代码与已通过远端 Debug job 的 `7ad7cd96` 完全一致，已用 `git diff 7ad7cd96 HEAD -- android-tv/app/src/main` 验证；后续提交补充的是测试与交付证据。

下一步：
1. 核对最新 Debug 的全量 JVM、lint 与 Debug 结果，以及 Android12 启动 job。
2. 核对模拟器的 19 项前置回归，再核对后续完整套件，特别是四模块设置/扫码/返回与首次分类缓存。
3. 获取最新 ABBA 同设备数据后再填写真实测量对比，保留日期/设备/样本条件；当前没有可报告的真实冷启动提速百分比。
4. 在有用户凭证和合法媒体授权的设备上验收实际 TMDB 内容与真实播放；元数据满足发行条件不能替代播放验证。

所有检查点继续留在当前 P0 分支；未合并主分支，未移除原 Draft PR。工作区 SDK/JDK/Gradle 工具不进入 git，APK 不作为源码提交。
