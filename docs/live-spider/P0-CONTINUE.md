# 最新断点：2026-11-13 Android CI通道实际恢复，双节点已运行

**环境通道已可用，P0播放仍FAIL；不要再以本地缺SDK作为停止条件。**
代码HEAD在本检查点提交前为faa8081a；宿主/双真实节点验证SHA为69419944。
[运行43395757545](https://github.com/shawnhhh/aura-tv/actions/runs/43395757545)由真实push自动触发：
A完整JVM727/727（0失败/跳过，M476为12/12）、Lint/Debug成功；B诊断APK构建成功；
C两份工件均确认KVM、模拟器启动、APK安装、正式MainActivity测试实际执行。
完整TV/生命周期260/260通过（0失败/跳过）；D已执行并正确因双节点失败返回exit1，
最终整体FAILURE。工件14022724331保存最终环境/节点汇总，不能把回归通过冒充真实播放。

本轮修复：6897d64d只补正文IO失败阶段事件，原NETWORK_ERROR/超时/断言保留；
78cd4b83拆开A/B/C/D并加专用push触发文件及手动入口；b6e1a159修复setup-android
默认退役tools包导致的真实失败；69419944允许新修正替代旧运行；26bc8321补严格仪器
完成门槛（本地4例通过；已用本轮真实下载工件离线复核，两份仪器均完成、失败仍拒绝，
未引发重复节点请求）。通道说明与操作见[CI-CHANNEL.md](CI-CHANNEL.md)。

奶酪本轮：真实配置/初始化/搜索/2026身份/29集/解析成功，DIRECT本机连接和发送成功，
等待响应头约1501ms超时；同次Spider回调约3908ms才返回200/HLS/InputStream，
8秒只读观察见URL引用及HTTPS目标，未取得最终远端状态。没有本轮403证据，不能沿用旧403
替代；没有创建Media3/真实首帧，不能靠放宽门槛宣称通过。
玩偶本轮：真实配置/初始化成功，普通/quick仍空正文，SOURCE_EMPTY_RESPONSE，未观察QR。
两者60秒/暂停恢复/Seek/重播/切集/详情返回再进均未通过。详细脱敏JSON见
[evidence/69419944-main-ui-cheese.json](evidence/69419944-main-ui-cheese.json)与
[evidence/69419944-main-ui-wanou.json](evidence/69419944-main-ui-wanou.json)。

原版既有脚本复用到当前分支，实证修复TAB无焦点、touch仅聚焦未切换，以及玩偶实际标签。
第二轮run43396931027@faa8081a：42个可见站点核查、仅两目标勾选、搜索实际执行；
奶酪标题可见，玩偶结果入口未观察到。不认无资源/必须登录，也未验证原版年份/详情/播放器。
这只解除了选站阻塞，尚不能把原版当真实播放成功对照；见[evidence/faa8081a-official-reference.json](evidence/faa8081a-official-reference.json)。

持久环境状态：[evidence/20261113-ci-channel.json](evidence/20261113-ci-channel.json)。
下一次读取实际CI及上面报告后，仅在有实质修复或新授权/网络依据时更新触发文件attempt/reason：

```sh
cd /workspace/aura-tv
git status --short --branch
# 修改 .github/p0-playback-trigger.json 后：
git add .github/p0-playback-trigger.json
git commit -m "ci(p0): run next evidenced playback repair"
git push origin HEAD:refs/heads/codex/herrin-p0-live-playback-20261110
# 无需本地SDK或Actions写API；Actions页面也可指定当前分支手动node=both/cheese/wanou。
```

PR保持Draft，不合并master；未使用Superpowers，未改无关UI，没有发布验收APK。

---

# 最新断点：2026-11-13 双真实节点 A/B 环境核查

**P0未完成；奶酪、玩偶哥哥本轮均 ENVIRONMENT_BLOCKED，原版 REFERENCE_BLOCKED。**
同步并核实代码HEAD `86ec4d419b6706be752967dd54cc499249e1d1d5`；本记录提交后的HEAD以
`git rev-parse HEAD` 为准。PR84实读Draft/open/unmerged，仍在旧global-search头08f6640；
未合并、未同步旧PR分支。未开发UI、未改生产播放路径、未使用Superpowers。

本轮新增核查：[脱敏证据](evidence/20261113-real-ab-blocked.json)。
- **补齐历史遗漏**：最后被动诊断run42882067450/job130060627476@d7ad546f，
  75项定向测试1失败（M476阶段事件），模拟器及真实节点步骤均skipped；没有新的媒体结果。
- 原版最新既有run42867039176/job130008047096实读日志：workflow success但正文
  REFERENCE_BLOCKED，停止于restrict_search_to_two_authorized_nodes，targets=[]。
  不得以此认定原版任一节点成功/失败或源站共同403。
- 奶酪最后有效Main UI证据仍0ada209：搜索/2026身份/29集/playerContent成功，
  前置探测无响应头即失败。更早86d3192才是本机502、上游403 HTML证据。
  玩偶最后有效Main UI仍0ada209：普通/quick空正文，无公开QR；不能判无资源或必须登录。
- 两者均未取得视频首帧，60秒、暂停恢复、Seek恢复新帧、重播、第二集、返回详情再进均未通过。
- 定向实读固定原版Proxy.java：原版直接chunked转发InputStream；本宿主先预读HLS、
  可取正文URL引用。这是静态实现差异；真实最早差异仍UNKNOWN，没有A/B因果证据，不据此打补丁。
- 最新CI实读run43378025637@83288e1a：宿主编译/Lint/Debug成功、JVM失败。
  727项/1失败为上轮已核查JUnit记录，不把宿主通过当整核接管。

当前环境无adb/SDK/emulator/KVM/javac，只有JRE21；无运行中的设备/现存测试APK/合法会话材料。
GitHub CLI Actions API实测Forbidden；连接器可读但未提供workflow dispatch工具。
完整编译/JVM/Lint/设备任务已合并尝试，wrapper先沙箱SocketException，外部重试为
services.gradle.org UnknownHostException（JVM未自动使用HTTP代理；不推断该域被策略拒绝）。
任务尚未开始，TV及生命周期未执行。整核检查仍INVALID：固定检出、四AAR、匹配媒体锁缺失。
未重试两个来源、未复验拒绝依赖候选、未请求猜测UA/Referer/Cookie、未绕过授权或DRM。

恢复条件：提供已配置SDK/Gradle/ARM或ARM转译的一次性设备与合法配置/授权及可访问来源的网络；
或恢复Actions运行权限。先恢复未修改官方APK的两节点选站UI，完成相同设备/网络/配置A/B，
不要先等整核编译。M476原断言继续保留；它目前会阻止既有专项工作流进入设备步骤，
后续需要修复被实证的事件遗漏并定向验证，不能删除或跳过测试求绿。

```sh
cd /workspace/aura-tv
git status --short --branch
git log -1 --format='%H %s'
# 在上述恢复条件满足后；两份独立报告，仍用既有正式MainActivity驱动：
LIVE_MAIN_UI=true LIVE_DISPOSABLE_DEVICE=true LIVE_NODE=cheese ARM_PACKAGE=true LIVE_INSTALL_ABI=armeabi-v7a LIVE_METHOD_METADATA=false LIVE_REQUIRE_ACCEPTANCE=true bash android-tv/scripts/run_live_nodes.sh
LIVE_MAIN_UI=true LIVE_DISPOSABLE_DEVICE=true LIVE_NODE=wanou ARM_PACKAGE=true LIVE_INSTALL_ABI=armeabi-v7a LIVE_METHOD_METADATA=false LIVE_REQUIRE_ACCEPTANCE=true bash android-tv/scripts/run_live_nodes.sh
# 有实际修复/新A/B信息或网络授权条件变化后才发请求；默认不重跑旧403/空搜索。
# 全部必要输入恢复后，在android-tv/执行完整验证：
# GRADLE_USER_HOME=/tmp/herrin-p0-gradle ./gradlew --continue :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
```

---

# 最新断点：2026-11-13 原版会话极简控制层与媒体能力

P0未完成。代码验证HEAD：`83288e1a40eb3fd931a8e37b794bb037a3041ef7`。
本记录提交后HEAD以 `git rev-parse HEAD` 为准。开发分支仍
`codex/herrin-p0-live-playback-20261110`，PR84实读Draft/open/unmerged，
head仍08f6640（旧global-search分支），本轮未同步它。

实质模块：bd4f170b（AV1/DV5/7/8及TrueHD/DTS能力）、930dac15（原版音轨/
字幕显式选择、关闭字幕、停用弹幕回调）、c3200fdb（whole会话独立极简UI与面板）、
4036c59c（遥控器长按暂停隐藏、Seek边界）、eda76c11/d018cad5（后台精确音轨
技术信息及空音轨兼容）。未创建第二播放器。详情与A/B比较见
[本轮记录](20261113-minimal-player-batch.zh-CN.md)。

底座继续A，B也受同一匹配媒体依赖阻塞，没有真实编译证据支持换底座。
原版完整core/adapter未编译；预检INVALID（未初始化源码/四AAR/定制Media3缺失）。
未重复下载全部原版源码、未复验拒绝候选。未切生产入口，旧实现删除0，未新增隐式fallback。
新UI尚无截图/TV仪器验证；DV/HDR/Atmos无实际输出徽标，未完成自动后端选择/真机透传。

统一验证run43378025637：
https://github.com/shawnhhh/aura-tv/actions/runs/43378025637
最终结果FAILURE：job131729029449，contract、宿主Kotlin/Java、Lint、Debug全部通过；
JVM727项，726通过/1失败/0 errors/0 skipped；新增KernelMediaAdmissionTest 2/2、
KernelSeekBoundsTest 1/1通过。原始JUnit已下载核查，工件14016120847，ZIP SHA256
`d4180ada39b663acc663483e817f192a9f4ee3c4bff4d83c2c6b620dd8199333`。
原M476第33行确认为delayHeaders=false，事件含RESPONSE_HEADERS_RECEIVED、
MEDIA_BODY_IO_TIMEOUT、MEDIA_IO_TIMEOUT，缺MEDIA_FAILURE_RESPONSE_BODY，验证下方根因。
未修改旧探测实现，未放宽断言。本地diff检查、Python语法通过；Debug wrapper下载时沙箱
SocketException，原版wrapper不存在退出127。无SDK/javac/adb/emulator/KVM。
早期run43377688737@69561c06宿主Kotlin/Java与contract通过后被最终批替换取消，
run43377935350@eda76c11也取消，不把二者算作最终完整结果。

遗留M476根因：正文IO内层catch只发MEDIA_BODY_IO/ MEDIA_IO，直接返回NETWORK_ERROR，
外层MEDIA_FAILURE_RESPONSE_BODY事件因此缺失；本轮仅为原断言添加事件序列诊断，
未改旧路径、不删除/跳过/放宽断言。不是已修复，不认定flaky。
TV/生命周期/焦点/宽高比/字幕音轨/HDR/音频设备分支、ABBA、搜索/网盘/直播设备回归、
奶酪和玩偶真实播放均未执行；没有新首帧/60秒/Seek/重播/切集/详情返回或QR证据。

下一轮准确续跑：

```sh
cd /workspace/aura-tv
git status --short --branch
git log -10 --oneline
# 已核实run43378025637@83288e1a；原M476正文catch遗漏phase事件，勿无变化重跑。
# GitHub CLI本环境API Forbidden，使用已连接GitHub fetch/workflow日志工具。
# 获取合法且精确匹配的输入后才stage/编译，不能从APK提取或用拒绝候选替换。
python3 android-tv/fongmi-kernel/verify_dependencies.py --directory /path/to/real-aars --report
# 有既有固定305dff2检出及真实锁之后，按fongmi-kernel/README.md stage并构建core/adapter。
# 然后才完成Application/同包冲突处理、生产接管与旧实现删除，再统一TV和真实节点验证。
```

---

以下为历史断点，不覆盖上述最新记录。

# 最新断点：2026-11-13 原版选择事务、宿主请求与严格来源优先级

P0未完成。代码验证HEAD：`f7ac613380b57e1cd5d621d7af425b77756b5b8a`；
最新兼容验证HEAD：`7063fe8dda15bbb269bcfe8956b565a659be48bb`；
本记录提交后的当前HEAD以 `git rev-parse HEAD` 为准。唯一开发分支
`codex/herrin-p0-live-playback-20261110`。PR84经GitHub连接器实读仍Draft，
head为`08f6640f8f3db9323fe0e08ac295c8aaa69ffaa7`，未同步代码，不自动合并。

本批连续完成三个代码模块，随后一次统一验证：

- `1a488c555a54a06c2f735bcb12f79ffe024abb92`：OriginalVodSession.beginSelection
  取消旧VideoViewModel工作并重新关闭执行许可；原版selectFlag更新状态后再允许
  明确选集，避免第二次切线先解析历史集。FongMiKernel.replay(long)改调原版
  PlayerManager.replay，消除controller→host→controller递归；解绑双PlayerView端口，
  snapshot保留错误码。没有重写原版状态机/解析器。
- `dd14d516a7189edf4978673987f1c45adc61ad4e`：新增纯Java KernelDispatch，
  Gateway统一使用。取消排队请求不进入内核，取消已委托请求同步取消其Future，
  调度拒绝/原版类LinkageError均明确结束UI请求。新增独立JVM契约检查并绑定check。
- `b8321b04ad7b4173d2b72f8daec8cdda36b83e56`：HerrinFilmRouter非流式路径
  现在同样遵守NAS→云盘→用户所选影视点→其他站点；所选资源验证失败才转其他。
  P0FastSourceSearchTest新增顺序、提前停止及验证失败后继续的回归；身份匹配保持。
- `f7ac613380b57e1cd5d621d7af425b77756b5b8a`：最后统一触发宿主check、
  Kotlin/Java编译、全部JVM单测、Lint及Debug。工作流run43355940615，
  https://github.com/shawnhhh/aura-tv/actions/runs/43355940615 。结果：宿主Kotlin/Java与contract通过，724项JVM中6失败。
  5项SMB因本工作流未启动原夹具，1项媒体超时阶段断言失败（根因未确认）。
  `9a1b6c7c`补齐原SMB夹具并先完成编译/Lint/Debug再跑全部JVM；
  新run43356896048中SMB启动成功，Lint暴露6个宿主兼容错误，JVM未执行。
  `8a2f2ffc`修复API26 Future和证据getter的Media3 OptIn；不改/跳过断言。
  最终统一run43357720463：Debug成功、JVM723/724（M476第33行阶段分类断言仍失败），
  Lint剩余1处getter声明，0d5c52cb已补。7063fe8d定向复核编译/Lint/Debug，
  最终定向run43358681621@7063fe8d的contract、Kotlin/Java、Lint、Debug全部成功；
  不把它替代完整JVM的723/724、0跳过失败结论，也不代表完整core/adapter或出帧。

真实修改文件：
`android-tv/fongmi-kernel/adapter/src/main/java/tv/herrin/kernel/android/{OriginalVodSession,FongMiKernel}.java`、
`android-tv/fongmi-kernel/contract/src/main/java/tv/herrin/kernel/KernelDispatch.java`、
`android-tv/fongmi-kernel/contract/src/test/java/tv/herrin/kernel/KernelDispatchContractCheck.java`、
`android-tv/fongmi-kernel/contract/build.gradle`、
`android-tv/app/src/main/java/tv/herrin/app/{HerrinKernelGateway.kt,HerrinSourceRouter.kt}`、
`android-tv/app/src/test/java/tv/herrin/app/P0FastSourceSearchTest.kt`及宿主CI工作流。

A依赖查证：只读取TV305dff2的构建定义、版本目录和三个相关Java文件，未重下/重审
1360文件或重测拒绝候选。原版仍以app/libs/*.aar为输入，版本目录未提供匹配
Media3/MPV的发布坐标。现锁artifacts=[]；DecoderMode、DiscMenuController、
SubtitleSelectionState、IsoDiscPreparation、PlayerView.setBackendPlayer及MPV定制
Java类缺少已证明兼容输入。hook/tvbus仍要求对应源码版本、许可证与构建材料。
不伪造二进制，不修改校验器，不从APK提取依赖。

本地环境：JRE21存在但无javac/Android SDK/adb/emulator/KVM；原版submodule目录为空。
遵照禁止重下全部源码的要求，没有执行submodule update。ECJ3.42从Maven Central
下载至/tmp，contract Java17编译通过，Cutover与Dispatch两组检查通过；
一条既有测试资源关闭warning保留。git diff --check及Python语法检查通过。
依赖report为INVALID（源码未初始化且四个AAR缺失），不是许可证验证通过。
完整core/adapter命令因原版gradlew不存在退出127；稳定宿主wrapper首次因默认
Gradle目录不可写，改/tmp后因沙箱网络退出。Android结果只认上方精确SHA CI。

B/C/D：仅上述边界修正，不声明原版Android类型/链接通过。已有wholeKernel页面、
Application、PlayerView准备代码继续保留；稳定生产入口未启用wholeKernel。
E：未正式切换、旧播放代码删除0、无隐式新旧回退接线。首页/海报/导航/直播未改。
奶酪、玩偶本轮各自ENVIRONMENT_BLOCKED：新完整内核未编译接管且无设备，未发节点
请求，未重复403。没有视频首帧/60秒/暂停/Seek/重播/第二集/返回详情结果；没有QR，
不能标AUTH_BLOCKED或授权成功，历史源端403/空正文不当作本轮新结果。

下一轮直接继续：

```sh
cd /workspace/aura-tv
git status --short --branch
git log -5 --oneline
# 先读 docs/live-spider/20261113-kernel-validation.zh-CN.md 的精确SHA CI结果。
# 已确认遗留M476阶段分类断言失败：原第33行。保留原断言及旧路径不补丁的约束。
# 仅从合法原始发布/对应源码取得匹配依赖；不要再验证两份已拒绝的媒体库。
python3 android-tv/fongmi-kernel/verify_dependencies.py --directory /path/to/real-aars --report
# 有已有305dff2检出及完整真实锁后再stage/core/adapter统一编译；不要以contract代替。
# 随后才能迁移Application/同包类、生产接线、删除旧入口，再运行TV/ABBA/双真实节点。
```

---

以下是历史检查点，不能覆盖上方最新状态。

# 最新断点优先：MainActivity真实驱动（2026-11-10 18:52 UTC）

**P0未完成。PR84仍Draft、未合并；集成头08f6640f8f3db9323fe0e08ac295c8aaa69ffaa7，与最新开发头不同。** 此集成头的JVM715/715、Lint/Debug/API31、TV259/259（原TMDB断言保留且通过）、ABBA24/24已全部通过：runs42859957975、42859957535、42859957426，安全摘要见evidence/08f6640-*.json。不要重复同HEAD完整CI或48节点/ARM能力基线。ABBA只是软件AVD小样本，不能声称全面提速。

开发分支已补当前明确选定、已授权Java Spider在无TMDB Token时的正式搜索，并增加 `FanTaiYingRealMainUiTest`：真实MainActivity配置/选站/搜索/详情/正式PlayerView，只读观察现有Media3，无注入影片/媒体/独立播放器。已配置TMDB路径及正式媒体校验不变。原创空首页UI三例在9c4f591/run42876168824/job130039922318通过；这只是原创夹具回归，不能当真实节点结果。

真实Main驱动两处错误已保留证据：699482a误认输入框为影片结果，搜索PASS已明确作废；9c4f591/e0c4c47为搜索超时、没有IPC SEARCH_STARTED。e0实证为输入4字且准确、Activity RESUMED、SEARCH_ROUTE_SELECTED_SPIDER。代码检查发现手写Thread.sleep轮询未推进Compose测试时钟，冻结正式LaunchedEffect的350ms防抖；578a0c0改用compose.waitUntil，不改真实播放的SystemClock/进度/出帧门槛。

**578a0c0/run42878654352/job130048672048已结束：时钟修正验证有效，两个节点均有真实SEARCH_STARTED/RETURNED。** 奶酪真实MainActivity搜索611ms、2026身份及29集详情1471ms通过；首次播放动作322ms在选集UI驱动AssertionError停止，尚未PLAY/RESOLVE，不能称新媒体失败。玩偶搜索423ms，普通/quick实际均0字符，SOURCE_EMPTY_RESPONSE，仍无QR。安全JSON见evidence/578a0c0-main-ui-*.json。后续仅补驱动对真实季集/分组季集控件的支持、精确固定动作/异常结构及公开授权回调事件；未改变pending-operation/会话归属、网络或播放器。继续查看此后最新双节点专项，先让奶酪完成真正选集/解析，再检查玩偶授权调用。不要把这些开发头称为完整CI全绿，不抢先集成未验证修复。

**更新：0ada209/run42880188825/job130054061906已完成，两个节点仍FAIL_PLAYBACK。** 奶酪正式SOURCE_EPISODE_BUTTON已确认，实际playerContent615ms，Health READY；正式前置探测约1508ms超时，没有HTTP响应头、没有创建Media3。原日志的MEDIA_CONNECT_IO_TIMEOUT只是通用catch标签，不能证明是连接阶段；没有新403/502实证。玩偶搜索442ms仍普通/quick均0字符；本轮已启用公开授权回调固定事件，但SDK事件[]、无QR。只能说未观察到公开调用，不能排除插件自带native网络/交互。

接下来**仅奶酪**增加被动证据：真实OkHttp连接/发送/响应头/正文阶段与DIRECT/PROXY类型（不记录地址），回调开始/结束耗时、服务器计算出的响应（区分客户端真正收到的状态）。原前置超时不变；失败后最多8秒只读既有诊断文件，等待同一次服务器回调的迟到结果，不重试、不新发媒体请求、不提升失败结论。没有新的源端合法依据，不再重复玩偶或相同403。先读此后最新定向运行，再决定具体修复，未通过真实与完整CI前不发APK。

较早但有效的媒体证据仍是：奶酪标准ARMv7正式HTTP首读READY→本机502→上游403 HTML，来源拒绝原因未解。两者没有真实媒体解码/60秒/Seek通过。新Main驱动的9c4/e0超时是驱动问题，不能当新一次源端403或空响应；578才实际重现玩偶空正文。无新合法证据不重复同一403，不猜造UA/Referer、不改插件保护、登录或DRM。

独立官方FongMi参考客户端只做到搜索范围UI控制焦点阻塞：3848b3c/run42867039176/job130008047096，REFERENCE_BLOCKED；没有目标搜索/媒体，不得推断官方来源结果。证据已保存独立参考分支6e687aa，停止重复参考UI构建。

精确现场续跑（已配置SDK/Gradle、标准ARM或支持ARM转译的**一次性测试设备**，会清理本应用测试配置，不用于私人已登录电视）：

```sh
cd /workspace/aura-tv
git status --short --branch
git log -1 --format='%H %s'
# 先用GitHub连接器读取上述运行；已授权gh环境也可：
gh run view 42878654352 --repo shawnhhh/aura-tv --log
# 只有修复或新合法证据需要重验时：
LIVE_MAIN_UI=true LIVE_DISPOSABLE_DEVICE=true LIVE_NODE=cheese ARM_PACKAGE=true LIVE_INSTALL_ABI=arm64-v8a LIVE_METHOD_METADATA=false LIVE_REQUIRE_ACCEPTANCE=true bash android-tv/scripts/run_live_nodes.sh
# 玩偶改LIVE_NODE=wanou；结果evidence/cheese.json或wanou.json。严禁默认YGP。
```

阶段修复验证后再普通快进集成，并在最终同HEAD统一完整CI。真实播放/CI未同时达标，不生成验收APK。正式16:57自动续跑已暂停，避免当前执行者并发写入；没有声称后台持续开发。

---

# 历史断点（2026-11-10 16:56 UTC）

P0仍未完成。源代码86d3192；PR84当前已验证应用HEAD adfbea4。空首页正式MainActivity两例已通过c8572a1；真实玩偶仍SOURCE_EMPTY_RESPONSE。奶酪标准ARMv7正式FongMi/Media3 HTTP首读已明确READY→本机502/上游403 HTML，未出帧。保留失败与安全JSON；旧常规715/257/24通过不能冒充新HEAD。

**先读[本轮最新中文检查点](20261110-P0-checkpoint.zh-CN.md)及最新PR/CI，再按未完成项继续。下方为历史记录，包含过期的“待运行/旧HEAD”，不能覆盖最新实证。**

16:57一次续跑已停用，因为当前已恢复执行，避免并发写入。没有启用额外后台任务。

---

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

## 最新断点（f7d9434代码完成定向，完整PR回归待启动）

- 最后真实双节点26bd09a /run42814282343 /job129823257320：奶酪仍HTTPS403 HTML→本机502，FAIL_PLAYBACK+BLOCKED；玩偶普通/quick各0字符，FAIL_PLAYBACK，根因未确定。两者首帧/Seek均false，无真实QR，禁止记任何播放/授权通过。完整安全JSON已入evidence/目录。
- 公开异常被动分类cb2aee4 /run42812579978 /job129817462751：匹配1个真实插件进程，未见所筛选的公开SDK/TLS/DNS异常；这不是完整原生网络可见性。
- 26bd09a收紧验收：真实60秒及进度/渲染帧、Seek新帧、详情年份确认、可用换线。发现当前测试宿主Back只证明播放器回调，现已禁止当详情通过；下一步仍须接实际MainActivity详情导航，当前会用PRODUCTION_DETAILS_UI_NOT_VERIFIED拒绝虚假验收。
- TMDB夹具过期之外还有取消替换刷新被loading直接丢弃。f7d9434按栏目Mutex排队；新增取消替换JVM回归。run42815327241 /job129826837608已success：JVM相关类；TV相邻5/5、慢焦点16125ms的原刷新用例1/1。原15/10/5秒断言未放宽。旧失败run42813770339保留，不能把早期仅修夹具当作已解决全部问题。
- 已核实集成目标仍79999f3且是当前修复分支祖先，准备普通快进集成，保持PR84 Draft。不要force push，不合并main/master。
- 普通CI增加本P0分支APK工件禁发；Debug/TV测试仍可构建临时诊断安装包。真实源未通过前不发布验收APK。
- 已建立正式一次自动续跑，计划2026-11-10 16:57 UTC，继承环境详情未返回；不能保证届时额度或机器可用，不能宣称当前后台在持续执行。先检查是否已有写入者，避免并发覆盖。

下一轮先执行/读取：

```sh
cd /workspace/aura-tv
git status --short
git branch --show-current
git log -1 --format='%H %s'
# 用GitHub连接器读取PR84最新head/draft及该精确head的完整CI。
# 最新真实双节点的已结束日志：job129823257320；不要无变化重跑403。
# TMDB定向已通过：job129826837608，不要重复构建这一组。
```

仅在有新修复/新合法来源证据、已配置SDK/NDK/Gradle及设备时：

```sh
# 只构建一次诊断包，复用两个节点；不要开启默认三测试架构基线。
cd /workspace/aura-tv
bash android-tv/scripts/build_live_arm_canary.sh
(cd android-tv && gradle -PherrinLiveSourceTest=true -PherrinArmTranslationTest=true -PherrinLiveAbi=arm64-v8a :app:assembleDebug :app:assembleDebugAndroidTest)
mkdir -p diagnostic-apks/arm64-v8a
cp android-tv/app/build/outputs/apk/debug/app-debug.apk diagnostic-apks/arm64-v8a/
cp android-tv/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk diagnostic-apks/arm64-v8a/
LIVE_NODES=both LIVE_NODE=cheese ARM_PACKAGE=true LIVE_INSTALL_ABI=arm64-v8a LIVE_PREBUILT_APKS=diagnostic-apks LIVE_METHOD_METADATA=false LIVE_REQUIRE_ACCEPTANCE=true bash android-tv/scripts/run_p0_selected_nodes.sh
```

本地当前无Android SDK/KVM，不应伪称上述设备命令已在此执行；真实执行使用Actions。
