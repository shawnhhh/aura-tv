# 2026-11-13 连续开发统一验证

代码基线4052140d；统一验证代码SHA：f7ac613380b57e1cd5d621d7af425b77756b5b8a。
P0未完成。稳定生产入口保持，完整原版core/adapter未编译接管。

| 范围 | 结果与限制 |
| --- | --- |
| git diff --check | 通过 |
| 原版依赖锁/许可 | 未通过：artifacts为空；本地未初始化固定submodule，四个AAR缺失，report INVALID；未伪造许可/校验通过 |
| 原版core/adapter Android | ENVIRONMENT_BLOCKED + DEPENDENCY_BLOCKED；原版gradlew缺失退出127，匹配定制Media3/MPV Java API依然没有真实锁定输入 |
| contract Java17 | ECJ3.42.0编译全部main/test Java通过；既有Cutover检查通过；新增Dispatch检查通过，含排队/委托取消、拒绝、LinkageError、正常完成 |
| 本地宿主Debug | 默认Gradle目录不可写；改用/tmp后沙箱网络SocketException；未完成Android编译 |
| CI宿主 Kotlin/Java/JVM/Lint/Debug | 统一run43357720463@8a2f2ffc：Kotlin/Java、Debug通过，JVM723/724，Lint1处错误。最后声明修正后定向run43358681621@7063fe8d编译/Lint/Debug/contract全部通过；完整JVM仍有失败 |
| TV模拟器、生命周期/焦点、首页/TMDB/NAS/直播设备回归、ABBA | 本地无SDK/adb/emulator/KVM，未执行；没有沿用旧SHA通过结果 |
| 奶酪 | 本批ENVIRONMENT_BLOCKED，未请求来源，未出帧/60秒/Seek/重播/第二集/详情返回，不复测已知403 |
| 玩偶哥哥 | 本批ENVIRONMENT_BLOCKED，未请求来源，未观察QR或授权，无播放通过；不推断AUTH_BLOCKED |

## 真实代码与交接

- 1a488c55：原版选择前取消旧任务并临时关闭执行许可；修复host.replay递归；
  解绑backend及普通视图，错误码保留到snapshot。
- dd14d516：Gateway委托纯Java KernelDispatch；UI取消/主线程拒绝/原版链接错误
  明确终止请求，无新增播放器/worker/解析路径；新增契约验证。
- b8321b04：非流式来源查找补NAS→云盘→所选影视点→其他严格顺序，保留身份匹配；
  两个JVM回归覆盖四种命中停止位置与所选验证失败继续。
- f7ac6133：统一宿主CI最后触发；不上传验收APK。

A只定向读取TV305dff2构建定义、版本目录、VodPlaybackController、VideoViewModel、
PlayerManager，未下载完整TV源码或重新审计1360文件；既有staging及校验器未修改。
原版构建仍引用本地app/libs AAR，版本目录没有确立可用媒体发布坐标。
没有新增有充分对应源码/许可/ABI证据的真实依赖。两个已拒绝候选没有复验。

B原版仍唯一VideoViewModel→VodPlaybackController→PlayerManager.parse路线。
C既有可选宿主页面/PlayerView接线准备继续保留，Gateway故障处理已修，未启用生产。
D本批仅补来源优先级一致性，未改全量TMDB搜索、用户授权范围或NAS设置界面。
E未达切换前提，Application未正式切换，旧实现删除0；没有宣称全功能移植完成。
首页/海报/导航/直播代码未修改；未执行设备回归不能推断通过。

GitHub连接器核实PR84 Draft/open/unmerged；实际head
08f6640f8f3db9323fe0e08ac295c8aaa69ffaa7，分支codex/herrin-global-search-20261107。
本轮代码仅推指定独立分支，不同步PR旧分支、不force push、不合并main/master。

下一起点见P0-CONTINUE.md最新首段；优先取得真正匹配、合法原始构建输入，
再完整编译原版Android与宿主。不能以本轮contract/稳定宿主结果替代完整core/adapter。

## CI第一次失败及配置修正

- run43355940615 / job131657902571 / f7ac613380b57e1cd5d621d7af425b77756b5b8a：
  contract两个检查、宿主Kotlin/Java及测试代码编译通过，724 tests / 6 failed。
  M476RemoteMediaProbeTest.delayedHeadersAndBodyHaveDifferentIoFailureStages的
  AssertionError位置为测试协程入口第15行；未取得逐断言定位，不认定flaky。
  五项SmbProtocolIntegrationTest失败含ConnectException；工作流没有像原android-debug
  一样启动真实SMB2/3夹具，现已复用原脚本补齐。首轮后续Lint/Debug未完成，不报通过。
- 9a1b6c7cc6f51c594832af262eac7b1e9a11d2ca：不改生产旧探测代码或任何断言；
  编译/Lint/Debug先完成，再独立运行全部JVM，max-workers=2，上传原始JUnit与报告。
  新run43356896048： https://github.com/shawnhhh/aura-tv/actions/runs/43356896048 。

## 第二次Lint结果及兼容修正

run43356896048 / job131660946099 / 9a1b6c7c：SMB2/3真实夹具启动成功，
contract及宿主编译通过，Lint失败6 errors/57 warnings，后续JVM未执行。
原报告工件14006777186已下载读取，ZIP SHA256
697f60e3550d3b45ced3a5a943850a13e5294c5c08767f262d1f5e6733435cf7。
两处HerrinPlayerSession.failedFuture要求API31，不适合minSdk26；
PlaybackPlayerEvidence三处、HerrinPlayerSession.skipSilenceEnabled一处UnsafeOptInUsageError。没有加入Lint基线或关闭规则。

8a2f2ffc9046fc92ca8f10d35cf8a1bb27ef9fa1：用API26可用的Future构造及
completeExceptionally替代两处failedFuture；证据属性getter明确采用Media3
UnstableApi；不创建新播放器、不改变证据门槛。CI使用--continue保留其他任务
结果，后续完整JVM不因Lint失败而缺席，整体失败仍保持失败。

## 第三次统一结果：保留旧媒体测试失败

run43357720463 / job131663622524 / 8a2f2ffc9046fc92ca8f10d35cf8a1bb27ef9fa1：
contract检查、Kotlin/Java、Debug assemble真实通过；Lint剩余1 error/57 warnings
（HerrinPlayerSession.skipSilenceEnabled）；全部JVM执行724项，723通过/1失败。
新增来源顺序回归、SMB测试没有列于失败清单。原始工件14006962974已下载解析。
M476RemoteMediaProbeTest.delayedHeadersAndBodyHaveDifferentIoFailureStages
真正失败栈为invokeSuspend第33行，即MEDIA_FAILURE_RESPONSE_HEADERS/BODY阶段分类
assertTrue；不是只凭协程入口第15行判断，尚未取得两个循环的实际event序列。
不认定flaky、并行Lint或源端问题，不修改旧探测路径，不删除/跳过/放宽测试。
该用例仍是未解决的宿主回归，不能报告全套CI通过。

0d5c52cbd001dee020be6b5693babed4b1bd673f补上漏处理的skipSilenceEnabled
getter OptIn。7063fe8dda15bbb269bcfe8956b565a659be48bb增加独立的最后兼容复核，
只检查contract、Android宿主编译/Lint/Debug，不重复已确定的旧测试失败。
原完整测试工作流及全部断言保留，其失败状态不会被此复核替代。

### JUnit原始XML核查

在8a2f2ffc的工件中，P0FastSourceSearchTest 13/13通过，failures/errors/skipped
均0；本轮新增batchLookup两例均通过。SmbProtocolIntegrationTest 6/6通过，
failures/errors/skipped均0。整个JVM仍723/724，不将这些子集冒充全套通过。

最终兼容复核：run43358681621 / 7063fe8dda15bbb269bcfe8956b565a659be48bb：
https://github.com/shawnhhh/aura-tv/actions/runs/43358681621 。

最终兼容复核 **SUCCESS**：run43358681621 / job131666736040，精确SHA
7063fe8dda15bbb269bcfe8956b565a659be48bb。contract两组检查、宿主Kotlin/Java编译、
Lint及Debug assemble通过，未上传APK。其通过只覆盖兼容性复核；不是完整JVM通过、
不是原版Android core/adapter通过、不是TV/ABBA或真实视频出帧。
全部原始JUnit XML汇总：724 tests / 1 failure / 0 errors / 0 skipped（8a2f2ffc）。

本批最终仍未完成：匹配原版媒体依赖/对应源码与许可、完整core/adapter编译、
生产Application/PlayerView切换与旧类清理、TV/焦点/生命周期/首页/TMDB/NAS/直播设备
回归、ABBA、两个节点各自合法授权和真实视频专项；遗留M476阶段分类断言保持失败。
没有验收APK，没有合并或同步PR84旧代码分支。所有代码提交已推指定独立分支。
