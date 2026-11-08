# Herrin TV P0：FongMi 多站搜索验收报告

本报告仅记录验收证据。应用分支 `codex/herrin-global-search-20261107`，Draft PR：https://github.com/shawnhhh/aura-tv/pull/84 。报告分支只提交文档；不作为安装包构建分支。

应用候选提交：`c585eeb4a275d7d0909de641b7db3906c1fe4d03`。报告分支只记录该提交的证据，不产生另一个应用候选。

## 状态边界

| 项目 | 状态 |
|---|---|
| 代码：站点发现、聚合、详情/选集、现有播放衔接 | 已实现 |
| 最终同提交 JVM/Lint/Debug/TV | c585eeb 全套运行中 |
| 原创协议服务、测试 DEX/IPC、真实视频字节/解码 | c624c67 已通过所有发现/选集/出帧回归；最终同提交结果待汇总 |
| 用户真实饭太硬/奶酪联网搜索及选集 | 未验证：没有同版本配置与授权样本 |
| TCL T7L Ultra 实际视频画面 | 未验证：没有真机连接与故障日志 |

## 三个故障的结论

### 影视点打不开 / 插件崩溃

没有取得用户当次 JAR、ABI 与异常堆栈，不能宣称已确认奶酪原生崩溃原因。代码中原自动搜源要求激活、搜索、详情、解析和媒体验证都在同一前台预算内完成；宿主回收并不等于插件 SIGSEGV。当前在明确 root/JAR 授权内，初始化与搜索事务串行，跳过无关 home 请求，节点失败隔离；沿用上一阶段脱敏堆栈、阶段和 OS 退出分类。新回归使用原创测试 DEX，在真实远程进程中聚合两站、杀死进程、恢复同一 VOD 并进入原 FongMi 流程，不能据此断言第三方 JAR 已修复。

### 1/44、10/44、成功 0 与反复继续

确认的宿主缺陷：旧路径把“搜到影片”和“媒体验证成功”混为一个成功指标，并以总 8 秒包住所有阶段；CMS/Spider 还先用精确标题、类型和年份过滤。真实响应条目可能在媒体 URL 获取前被丢掉，成功 0 不能解释为全站没资源。

现使用独立发现服务：个人源短预算 → 明确手动首选 → 其余来源；CMS 并发上限 4，Spider 从初始化到搜索整个事务串行。每个已执行节点有独立预算，队列等待不耗其执行预算，不再 8 秒强制中断发现。其他已导入仓在首选后有界并发读取元数据，只展开兼容 CMS，不获权执行其 JAR。逐站发布；成功、无结果、失败、超时、未完成分别统计。取消的任务保持未完成；继续跳过终态，保留结果和各源选集布局。配置变化按稳定 root/site/API/JAR/ext/header 身份失效清理。

实际调用顺序由 `P0AggregatedDiscoveryTest` 记录：已配置个人源依次结束 → 手动首选站初始化/搜索 → 其余节点与其他仓元数据展开；CMS 同时最多 4 个，Spider 初始化与搜索事务同时最多 1 个。未配置个人源不建任务。此为受控协议测试顺序，不是用户奶酪节点的实机调用记录。

### TMDB 有结果但无选集 / 无法播放

搜索响应保留 provider VOD 和来源，不要求先解析视频。用户选某来源后才调用该站 detailContent / CMS ids；选某集后才 playerContent → 原 FongMi ParseJob/Proxy → 媒体响应检查/Media3。各源选集布局只保留集号、集名和线路 ID/名称，不缓存媒体授权；重新选择仍读取当前详情。明确错误 VOD 拒绝，不用 TMDB ID 替换 provider ID。

补齐 FongMi type=4 的 `wd/quick/extend`、长 ext POST、opaque 集 ID 及 `play/flag/extend`；补 type=0 RSS/XML 与 Android DOM 安全适配。各 CMS 保留所属仓解析配置。失败保留其他来源和换线、重试、返回入口。显式最近卡片续播从当前已授权结果严格匹配和重取选集，原历史坐标与返回导航保留。`99959a1` 进一步发现播放器根布局先于实际请求挂载的竞争；个人源在明确所选集数后执行 header 与 1 KiB 实际媒体检查，通过后才进入原播放器，原请求数断言保留。

## 上游对照

实际读取 FongMi/TV `4512b4c9f82534ff66acfde06ccdd68f35b074f0`：SiteApi 的 searchContent/detailContent/playerContent、SiteViewModel 逐站结果和 ViewModelSearchRunner 的独立任务/epoch 取消。完整映射与永久链接见同目录 `P0_FONGMI_DISCOVERY_20261108_ZH.md`。

没有用用户配置进行 Herrin/TVBox 同版本逐请求对比，不能把源码对照当作奶酪实际网络差异证据。

## 提交

| 提交 | 模块 |
|---|---|
| d516f97 | 独立发现服务、流式多源、来源分组和独立选集入口 |
| 2f4ddbf | HTTP Spider / XML CMS 协议及原 FongMi 接入 |
| 263d16a | Spider 原始 VOD 样本修正、双 CMS Activity 集成 |
| a9d30e2 | IPC 原始/解析数量、阶段与错误分类、脱敏诊断 |
| 9c8396e | 多仓动态展开、取消继续、显式最近续播 |
| 9f6c32d | 根据丢键日志修正 IME 连接重启、配置失效清理 |
| 99959a1 | 各来源选集布局保留、聚合 Spider 到实际帧、必跑门禁 |
| c5ef16c | Android DOM 正向/DTD 负向回归及安全兼容 |
| 383b84d | 据保留语义树同步缓存测试入口，保留快速切换原断言 |
| 52e4fcf | 所选个人媒体的真实响应门禁、已关闭 IME 不重复交接、17 项原断言预检 |
| c624c67 | 新展开仓的授权元数据仅存内存，配置变化清理，增加不落盘回归 |
| 8ac4afd | 据 InputDispatcher 真日志补齐后台恢复测试的实际窗口输入就绪检查 |
| 50d07d5 | 据 Google TV IME 日志同步测试输入视图生命周期，保留真实遥控与原断言 |
| c585eeb | 据实际堆栈/按键日志同步刷新后的设置状态行和重启后的收藏详情动作 |

新展开仓通过 `forSearch(...persistFetched=false)` 读取配置，进程内最多保留 20 个根，不写出可能包含 Cookie/Token 的配置正文。配置变更使旧任务失效。保留原有仓缓存和直播行为；这不等于宣称已对历史缓存完成加密迁移。本轮不改 NAS/云盘设置、凭据迁移、SMB 或索引。

## 保留的失败记录

| 提交 | Debug | TV |
|---|---|---|
| d516f97 | 640 项，1 失败 | 233 项，4 失败 |
| 2f4ddbf | 646 项，1 失败 | 234 项，1 失败 |
| 263d16a | 646/646、Lint/Debug 通过 | 235 项，2 失败 |
| a9d30e2 | 648/648、Lint/Debug 通过 | 235 项，2 失败 |
| 9c8396e | 652/652、Lint/Debug 通过 | 235 项，1 失败；最近续播已通过 |
| 9f6c32d | 652/652、Lint/Debug 通过 | 235 项，1 失败；搜索遥控与续播均通过 |
| 99959a1 | 653/653、Lint/Debug 通过 | 235 项，2 失败；聚合 Spider 实际帧通过 |
| c5ef16c | 653/653、Lint/Debug 通过 | 236 项，3 搜索遥控失败；原生 XML 回归通过 |
| 383b84d | 653/653、Lint/Debug 通过 | 236 项，1 个原搜索遥控用例失败 |
| 52e4fcf | 655/655、Lint/Debug 通过 | 236/236、17 项预检全部通过 |
| c624c67 | 655/655、Lint/Debug 通过 | 237 项，1 个后台恢复输入权限失败；所有搜索/聚合/新隐私回归通过 |
| 8ac4afd | 655/655、Lint/Debug 通过 | 237 项，1 个分类确认失败；后台恢复及全部协议/出帧用例通过 |
| 50d07d5 | 655/655、Lint/Debug 通过 | 237 项，2 个设置/收藏就绪失败；所有搜索/多站/恢复/出帧用例通过 |
| c585eeb | 运行中 | 运行中 |

M437 元数据测试共用的 CMS 详情样本被交给不同 Spider VOD，新增校验正确拒绝；仅改为该 Spider 的实际请求 ID，所有原断言保留，独立错误 ID 拒绝用例保留。NAS 最近续播失败由发现/详情拆分引入，使用原回归确认修复。搜索确认/Down 丢失的日志表现为 IME handoff 后有 UP 无 DOWN；不再对已关闭、已隐藏的编辑会话重复 hide/restartInput，保留原真实遥控测试，并将三个原用例加入预检（14→17；整套仍必跑）。缓存首页已由独立证据作业 42432997195 定位入口等待与缓存发布竞争：01:03:04.529 的首次断言之后 127 ms，保留的语义树已有正确标题 Original 11 和同片背景。原生产代码明确骨架与缓存异步发布；测试在相同 10 秒内等待遮罩消失且缓存首片存在，再执行全部 12 次快速分类即时标题/背景/焦点断言。没有改变首页、启动动画或性能门禁，也没有扩大原等待时间上限。

`c624c67` 失败保留在 [TV 作业 42435249433](https://github.com/shawnhhh/aura-tv/actions/runs/42435249433) 与 [artifact 13599294441](https://github.com/shawnhhh/aura-tv/actions/runs/42435249433/artifacts/13599294441)。[只读检查 42436457383](https://github.com/shawnhhh/aura-tv/actions/runs/42436457383) 读取其完整 logcat：01:52:52.850 已解码 7 帧，01:52:54.032 暂停位置三次均为 1025 ms；01:52:55.467 输入目标仍为 `InstrumentationActivityInvoker$EmptyActivity`（uid 10077），实际注入方 uid 10076 被拒，失败栈为恢复后 MEDIA_PLAY 的第 141 行。测试 Activity 的 RESUMED 回调早于测试辅助窗口交接完成；新增原 15 秒 await 工具内的 `hasWindowFocus()` 检查后再发送原键，未获取额外输入权限、未重发按键、未改播放器或原出帧/暂停/ENDED/释放断言。

`8ac4afd` 的 17 项预检全部通过，完整 TV 仅分类确认失败，保留 [42436598370](https://github.com/shawnhhh/aura-tv/actions/runs/42436598370) / [13599799580](https://github.com/shawnhhh/aura-tv/actions/runs/42436598370/artifacts/13599799580)。[日志检查 42437890045](https://github.com/shawnhhh/aura-tv/actions/runs/42437890045) 确认：02:09:22.586 已注入 CENTER，Google TV IME 到 22.675 才 onFinishInputView、22.690 onDeactivate，Activity 在 22.733 仅收到 UP。语义文本/焦点操作加 Insets/isAcceptingText/无障碍 idle 不足以证明真实 IME 已交接。依据 API30 InputMethodService 的实际 dump 字段，测试先等待真实输入视图启动再设置文本，离开编辑器后等待服务 mInputViewStarted/mWindowVisible 均 false 再发原遥控键。仅读取两个布尔值，不导出 EditorInfo/原始 dump，保留原 5 秒交接检查上限及全部分类/排序/详情断言；生产搜索不改。

`50d07d5` 保留 [失败 run 42438102723](https://github.com/shawnhhh/aura-tv/actions/runs/42438102723) / [artifact 13600399498](https://github.com/shawnhhh/aura-tv/actions/runs/42438102723/artifacts/13600399498)。原 onlineSearch、分类排序、完整多站、选集、恢复及出帧回归均通过；设置在刷新后滚动状态行时，由错误语义树打印触发 instrumentation/main 的 SnapshotStateObserver 线程竞争，新增与同测试保存流程相同的 8 秒实际状态行就绪检查。收藏日志确认 02:29:41.007/009 原 DOWN/UP 和 41.010 DETAILS 调用，41.052 才提交详情路由；新增 5 秒实际已收藏动作就绪检查，再执行原焦点/遥控取消/返回空结果断言。未改设置或收藏生产实现、未重发用户操作、未删除原断言。

## 最终 CI 与 APK

| 工作流 | 当前状态 |
|---|---|
| [最新 Debug / JVM / Lint / APK](https://github.com/shawnhhh/aura-tv/actions/runs/42439821473) | 运行中 |
| [最新 Android TV](https://github.com/shawnhhh/aura-tv/actions/runs/42439821549) | 运行中 |

[上一提交 50d07d5 Debug APK](https://github.com/shawnhhh/aura-tv/actions/runs/42438102698/artifacts/13599914101)（不是最终同提交交付；GitHub artifact ZIP，下载需仓库访问权限）。ZIP 内 `app-debug.apk` 为 26,642,135 字节，原始 APK 的 SHA-256：

```text
42ccfdfc3ae39a7669e6111040b26505e64852755b91fb5ab005ca1997e0ebc7
```

该哈希由 CI 直接计算，不是 ZIP 的哈希。最终同提交 APK/SHA-256 等待 `c585eeb` 完整验收后更新，不以旧 APK 替代。

[50d07d5 JVM/Lint 逐项报告](https://github.com/shawnhhh/aura-tv/actions/runs/42438102698/artifacts/13599963899)。最终同提交 TV 证据待本轮工作流结束补齐。

JVM 协议用例使用 CI runner 上的 loopback HTTP 服务与实际 JSON/XML 编解码；调度用例使用受控任务。TV 为 API 30 x86 模拟器、原创授权 DEX 与原创媒体。真实字节和视频解码帧可以证明适配链路执行，不能证明第三方站可播。未删测试、未跳过断言、未降低性能或必跑门禁。

## 回归覆盖对应关系

| 验收要求 | 测试与证据范围 |
|---|---|
| 指定 CMS、原始 provider VOD 详情 | P0AggregatedDiscoveryTest 的 realCmsSearchKeepsAnnotatedTitleAndUsesProviderIdForDetails；实际 loopback HTTP |
| 指定 Spider、多个站点同片均保留 | P0DiscoveryProtocolTest 的原创授权 DEX，通过真实 Binder 调用 searchContent(false)，两站结果各自保留 |
| 搜索命中时媒体暂不可用 | sameTitleFromMultipleSitesSurvivesUnavailableMedia；双 CMS Activity 用例确认选择前详情/媒体请求均为 0 |
| 超时隔离、44 节点取消/继续、首选和并发 | oneTimeoutDoesNotHideFastHitsAndCountersAreTerminal、fortyFourSitesCancelAndContinueWithoutRepeatingCompletedNodesOrLosingHits、cmsConcurrencyIsBoundedAndSpiderLifecycleIsSerial；受控任务，不是 44 个真实第三方节点 |
| 多仓、配置身份变化和授权边界 | warehouseExpansionStartsAfterPreferredAndStreamsNewProvidersWithoutClearingHits、configuredWarehouseMetadataIsFetchedWithoutDownloadingItsJar；真实元数据 HTTP，不下载/执行该仓 JAR |
| 多来源详情/线路不串线 | explicitWrongSpiderDetailVodCannotMixLines、providerEpisodeLayoutsSurviveContinuationAndOtherFailuresWithoutCachingUrls；错误 VOD 拒绝且保留其他来源 |
| Spider 进程重启后恢复并实际出帧 | twoSpiderSitesRetainHitsAndRestoreExactVodAfterWorkerDeath；实际杀死隔离进程，再查询相同 provider VOD、原 FongMi playerContent/Proxy、媒体读取及至少 4 帧 |
| HTTP Spider opaque ID、XML、FongMi 衔接 | P0HttpSpiderProtocolTest 与实际 Android DOM 用例；HTTP Spider 经原 FongMi 到原创 MP4，至少 4 帧 |
| 授权不落盘与媒体真假 | expandedWarehouseCredentialsStayInMemoryAndContinuationDoesNotRefetch；P0SelectedPersonalPlaybackTest 实际 HTTP 头/媒体字节，拒绝伪视频 HTML |
| 既有搜索/播放器/直播/启动/遥控 | 全套不加过滤；必跑类门禁仍要求原 FongMi、JSON/WebView/HLS/Proxy、直播、收藏历史、启动与遥控性能类 |

## 不变性与环境限制

本地结构检查与 Python 播放边界回归通过，43 个固定上游 FongMi 文件校验通过。导航与直播播放器代码块相对 689f4da 字节一致；直播、M3U、EPG、原 TMDB 在线搜索、首页/海报/original 背景/评分样式均未改。未使用 Superpowers。#83/#84 保持 Draft，不合并、不强推、不覆盖并行分支，不重置设置或加密凭据。

本机无 Android SDK/adb，Gradle wrapper 下载在沙箱被拒；正式构建/模拟器由既有 GitHub CI 执行。连接器提供 CI artifact 引用后，本执行环境下载证据 ZIP 得到 HTTP 403，验收采用 CI 保留原始日志/逐项报告，不虚构本机执行或文件下载成功。

## TCL 待验及恢复方式

使用用户合法授权的同版本饭太硬配置/JAR，在 TCL 选择奶酪查询小城良方，采集脱敏阶段、raw/parsed/discarded、原始 VOD、详情选集数与播放器错误。与 TVBox 同身份逐请求核对。验证真实选集、多站切换、HLS/MP4/JSON/WebView/合法 proxy、实际画面、拖动续播、断网恢复和长片。确认旧安装与新 APK 签名兼容及设置保留；不通过卸载清数据解决签名冲突。

真机复现时，从“设置 → 高级工具与诊断 → 复制诊断摘要”导出已有脱敏摘要，包含 SOURCE_SEARCH、节点阶段、耗时、完成数及 raw/parsed/discarded。可在已建立 ADB 连接的电脑执行以下仅采集安全固定字段的日志命令，再复现一次搜索；它不授予 JAR 权限，也不记录配置正文或媒体地址：

```sh
adb shell setprop log.tag.HerrinSource DEBUG
adb logcat -v threadtime -s HerrinSource:D HerrinSourceFeedback:I HerrinSpiderWorker:W
```

应记录实际站点身份摘要/配置版本、TMDB 条目及标题、按来源的搜索条目数、选择的 provider VOD、选集数、playerContent/ParseJob/Proxy/Media3 阶段和首帧事件。原始私有配置、Cookie、Token、签名 URL 不进入报告。当前没有这些用户实源证据，不能补写成已通过。

续跑先核对 Draft #84 与远端 HEAD/CI，再使用当前应用分支：

```sh
git fetch origin
git switch codex/herrin-global-search-20261107
git status --short
git log -8 --oneline
cd android-tv
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

没有真实源或 TCL 证据时，只能称代码/协议自动化完成，不能称最终实机标准通过；没有后台继续开发承诺。
