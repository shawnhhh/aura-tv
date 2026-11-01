# M4.67 Agent 4：播放失败与恢复移植验收记录

工作树 `/workspace/herrin-m467-recovery`，分支 `codex/m467-playback-recovery`，开发基线 `cd6deb3d116969f36e451be6ba6dd8649dc4b23f`。
上游固定为官方 FongMi/TV `fongmi` 的 `5e191aaced194e5cac5668cc91908ecb65336222`。

## 上游方法映射

| 上游文件/方法 | Herrin 文件/方法 | 状态/适配原因 | 自动化用例 |
| --- | --- | --- | --- |
| `player/exo/ExoPlayerEngine.java#handleError`、`#seekToDefaultPosition` | `FongMiPlaybackRecovery.classify/Session.next`、`AuthorizedPlaybackPanel.onPlayerError` 的 `SEEK_LIVE` | 实际移植 behind-live-window → seekDefaultPosition + prepare；宿主追加每个显式播放选择最多两次，避免断流循环 | `FongMiPlaybackRecoveryTest.liveWindowUsesUpstreamSeekDefaultAndCannotLoopForever` |
| `player/PlayerManager.java#handleRefreshError`、`refreshAttempted` | `FongMiPlaybackRecovery.Session.refreshAttempts`、`AuthorizedPlaybackPanel` 的 `REFRESH_SOURCE`、原有 `onRefreshSource` | 原上游一次 refresh callback；保留一次预算，HTTP410失效或实际本机代理连接故障才刷新；根据用户安全要求收紧403/401/429，不盲目重试/绕鉴权 | `expiredResultRefreshesOnlyOnceAcrossReplacementPlayerInstances`、`loopbackConnectionLossRefreshesSourceRatherThanSelectingSoftwareDecoder`、`M467PlaybackRecoveryTest.goneResultRefreshesOnceAndBudgetSurvivesPlayerReplacement` |
| `player/PlayerManager.java#reset` | `MainActivity` 的 `remember(catalogScope,film.id,episode,line,screen)` 与 `Session.userRetry` | reset 绑定明确选择/用户重试，不随解析URL/播放器重建重置。MainActivity仅增加预算创建与播放参数连接，不改变任何UI/资料/选择交互 | `expiredResultRefreshesOnlyOnceAcrossReplacementPlayerInstances`、`onlyExplicitUserRetryStartsNewRecoveryBudget` |
| `player/PlayerManager.java#onPlayerError` 的 REFRESH/RECOVERED/FATAL | `AuthorizedPlaybackPanel.onPlayerError` | 真实Media3状态码、异常链先分类；网络读取/EOF Range中断有限退避；403/401/429、TLS、DRM、权限最终错误；原有明确备用URL和MIME尝试保留但禁止拒绝后继续自动换URL | `denialsNeverRefreshRetryOrChangeMimeEvenWhenWrappedInNetworkError`、`tlsDrmPermissionsAndDecoderFailuresAreNotRetriedAsTransport`、`M467PlaybackRecoveryTest.forbiddenMediaNeverRefreshesOrRequestsProviderBackup` |
| `player/PlayerManager.java#onPlayTimeout` | Panel 的 `LaunchedEffect(player,playbackState,playbackForeground)` | 每个前台BUFFERING区间15秒实际加载期限；到期先stop再进入真实TIMEOUT分类，适用同一有限恢复预算；后台取消期限 | TIMEOUT分类/预算JVM覆盖，15秒真实仪器到期尚未执行 |
| `player/parse/ParseJob.java#stop`、`PlayerManager#stop` 的取消/释放语义 | `Session.cancelPending/isCurrent`、Panel `ON_STOP/onDispose` | 宿主Coroutine Job取消 + generation fencing，释放后延迟恢复不能重新prepare；预算不因释放重置 | `cancellationInvalidatesDelayedCallbacksAndDoesNotResetBudget`、现有 `PlaybackSessionIsolationTest` |
| 上游单进程 `BaseLoader/JarLoader` 生命周期 | `TvBoxSpiderRemote.TvBoxSpiderBridge#request` | 必要宿主ABI适配：Herrin独立`:spider`进程，保留原IPC接口；只有主机确实观察到Binder死亡/断开/超时才重建上次成功授权的站点并重放当前操作一次；不重试失败browse/普通插件初始化错误。关闭或取消使延迟重建失效 | 原有 `SpiderRemoteProcessTest`（需仪器运行），精确故障注入进程重建尚需补充，不能宣称全部验证 |
| 上游代理加载生命周期，宿主已有 `play` health重连 | `FongMiPlaybackRecovery.canRebuildProxy`、Remote `play` | 修正旧代码任何health失败均重建，只有连接拒绝/超时/IO/5xx可恢复；403/无效session/port/路由停止，不能复活未经授权的会话 | `invalidOrDeniedProxySessionIsNeverRebuiltBlindly` |
| 上游 `PlaybackLog` 的真实错误分类 | `PlaybackErrorReport.media/source` | 保留宿主更严格的日志脱敏，输出错误码/状态/固定异常类型/分类，不输出URL、host、请求头、Cookie、Token或插件原始异常文案；补充初始化固定标签 | 现有 `PlaybackErrorReportTest`、`PlaybackFailureHintsTest` 与新 `unknownPluginMessagesDoNotBecomeRecoverableAndDecisionsContainNoSecrets` |

## 恢复边界和取消

- 远端网络/媒体EOF/短暂DNS与断链/HTTP5xx共用两次预算，退避500ms、1000ms。重复READY不会重置，HLS后续分段故障不能无限重试。
- 重新打开当前Media3 DataSpec保留当前位置和Range语义，不捏造新URL、请求头或权限；当前解析失效/代理断连只重新解析当前影片/集/线路。
- 局域网/本机代理失败不进入软件解码。解码后备由Agent3引擎能力负责，Agent4不添加空实现。
- 上游HTTP错误刷新策略按用户明令限制：401、403、429、网络权限、TLS/DRM拒绝均不自动重试。404保持已存在的失败交互；410明确表示结果失效时允许刷新一次。
- `PlaybackFormatRetryTest.denialDoesNotProbeMimeAndRateLimitStopsAutomaticRequests` 原403期望“自动尝试备用URL”与用户约束冲突；修复实现同时将该断言改为停止，并补充401/403禁止重试、拒绝后预算终止的回归断言。这是修复已识别的授权策略缺陷，不是隐藏失败。
- `close()`、离开播放、后台、媒体替换都会取消延迟回调；只有用户明确重试可复位预算。恢复不修改选线路或具体集数点击行为。

## 许可证

`FongMiPlaybackRecovery.kt` 源文件注明上游SHA、方法、原FongMi贡献者归属、GPL-3.0-only和本次修改说明；遵循现有 `android-tv/LICENSE-GPL-3.0.md`。宿主协程恢复无新增第三方依赖、原生库、签名运行时或第三方片源。上游mpv/FFmpeg/native许可及可获取性由Agent3独立记录；本提交不宣称已取得这些模块。

## 验证证据及剩余缺口

纯JVM生产逻辑隔离编译与JUnit运行完成：`/workspace/logs/recovery-jvm/compile.log`、`/workspace/logs/recovery-jvm/tests.log`；5个类共36项通过（含新增11项），Kotlin 1.9.23编译器及JUnit4来自Gradle8.9发行包。不是Android/JVM全量。新增JVM测试11项（故障分类/预算/退避/取消/授权隔离/根因脱敏）、真实HTTP驱动Media3仪器测试2项（410换播放器后仍有限、403不访问备用URL）。仪器用例存在不代表已执行；TCL第三方片源成功从未验证。

已实现的故障恢复不等于完整FongMi播放链路移植。尚无故障注入JAR覆盖每个Spider进程死亡时点和加载超时；JAR/网页/JSON聚合解析取消和native解码后备依赖其他Agent与集成测试。不得用本文件声称所有恢复路径已通过。

Android整体编译与仪器编译交由总集成执行：本提交Panel依赖Agent3的`FongMiExoPlayerFactory`/`FongMiSubtitleFormats`提交，不复制拥有权。请先集成Agent3再集成本提交。新增仪器用例尚未执行，不能计入通过数量。
