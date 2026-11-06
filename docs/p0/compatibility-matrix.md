# P0 阶段 A：生产链路与固定上游依赖核对

核对日期：2026-11-04 UTC。Herrin 基线：`ed6217312e88b9cb0abd0edaf5af5331776d3906`。
固定上游：FongMi/TV `305dff233cde512e2c83f71529b7c3ecfd61fefc`，GPL-3.0。
本记录更新旧兼容矩阵的现状，不是另起开发计划，也不表示 B/C/D 已验收。

2026-11-05 更新：`f11be020832766e5ba51536b8bb5b72cc5d6febc` 的串行 CI
[41953150985](https://github.com/shawnhhh/aura-tv/actions/runs/41953150985) 已通过：
Debug 533 项 JVM、TV Emulator 179 项，均无失败/跳过；对应 APK 和证据已上传。
随后进入阶段 B 的 [ParseJob 方法核对及 Config 默认选择子契约](parsejob-method-audit.md)。
下表为最初审计快照，不应再把“没有最新 CI”或 vendor 全未编译的历史描述当作当前阻塞。
Sardine 已单独接入源码编译；FongMi vendor 原版仍不整体参与编译。完整 B/C/D 和 TCL
验收仍未完成，最新结果以 cloud-progress.md 对应提交与实际运行记录为准。

## 七个环节的实际状态

下表生产文件均相对于 `android-tv/app/src/main/java/`；原版源码相对于
`android-tv/vendor/fongmi-tv/app/src/main/java/com/fongmi/android/tv/`。

| 环节 | 固定上游 | 当前生产实现及已移植行为 | 尚未闭合的契约 |
| --- | --- | --- | --- |
| Config | `api/config/BaseConfig.java`, `VodConfig.java` | `tv/herrin/app/TvBoxWarehouses.kt` 的 `TvBoxConfigParser`、`TvBoxWrappedConfig.kt`、仓库选择与授权配置 | 上游 Config 未编译接入；现有单/多仓模型与 VodConfig/站点/解析器配置还需逐项映射，不能仅改类名 |
| Loader | `api/loader/BaseLoader.java`, `JarLoader.java` | `TvBoxSpiderBridge.kt` 的 `TvBoxSpiderWorker`：受批准 JAR、DexClassLoader、Init、站点实例、静态代理、生命周期 | 不是原版 BaseLoader/JarLoader；JS/Python/node 未获授权不得执行，不能宣称全运行时兼容 |
| Spider/playerContent | CatVod Spider + loader | 同一 worker 的挂起播放入口：调用实际 playerContent，`NativeFongMiRoute.playerContent` 后交 ParseJob；可取消阻塞插件调用 | 合法第三方真实插件及 TCL 仍待验证；fixture 成功不能等同外部源成功 |
| ParseJob | `player/parse/ParseJob.java`, `ui/custom/ParseWebView.java` | `FongMiParsePlayback.kt` → `fongmi/parse/ParseJob.java` → `FongMiBrowserPlayback.kt`；JSON、扩展、聚合、WebView、期限与取消 | 运行源码版权头仍标注较早 `5e191aaced194e5cac5668cc91908ecb65336222`；需对固定版本逐方法比对并记录宿主差异，不能更改版权头冒充完成移植；最新期限/启动失败测试尚无 HEAD CI |
| Proxy | `server/process/Proxy.java`, `server/Nano.java` | `nativeplayback/NativeFongMiProxy.java`、Route、Hls；回环随机端口、会话/站点认证、原始 do 分发、响应状态/头/流、HLS 重绑定 | 当前为明确宿主适配而非原 Nano 全服务器；需要最新 HEAD 的 HTTP/鉴权/真实媒体回归，不再扩旧 do 白名单 |
| PlayerManager/MediaItem | `player/PlayerManager.java`, `player/media/MediaItemFactory.java`, `PlaySpec.java` | `AuthorizedPlaybackPanel.kt` → `FongMiExoPlayerFactory.kt`；`nativeplayback/FongMiPlaybackAdapter.java` 共用 resolved-URI 边界 | 原 PlayerManager/MediaItemFactory 仅暂存；Compose 仍持有唯一生产播放器，不能宣称由上游 PlayerManager 完整接管；`prepareMedia/prepareResolved` 辅助入口不等于生产 UI 已切换 |
| Exo | `player/exo/*`, `player/engine/*` | 发布版 Media3 **1.9.4**、移植的 `com/fongmi/android/tv/player/exo/ExoMediaSourceFactory.java`、Herrin 控件/恢复；`FongMiExoMediaSession` 为宿主桥 | 上游 fork 的 ISO/光盘导航、字幕/特效和引擎依赖未完整接入，不能用标准 Media3 构建替代这一差距 |

## 必要依赖与许可证

`app/build.gradle.kts` 可核对现有 OkHttp 4.12.0、Gson 2.14.0、Guava
33.7.2-android、NanoHTTPD 2.3.1 和各 Media3 1.9.4 模块；`settings.gradle.kts`
目前只包含 `:app`，vendor 目录未作为 source set 编译。
上游 `MediaItemFactory` 直接使用 `IsoUri`、`LocalClearKeyLicense`、`setAdblock`；
PlayerManager 还使用光盘导航/ISO 准备等扩展。未提供可重现、许可证明确的定制
Media3 依赖前，不得直接把这些 vendor 文件放进 main 或用 stub 伪装接口齐全。
MPV/libass 与其他非仓库 AAR 的存在不能凭推测认定。

43 份原版由 `UPSTREAM_SHA1.txt` 和 `verify_fongmi_vendor.py` 校验 Git blob；
校验仅证明暂存内容完整，不证明已编译、已执行或已解码。保留 GPL 全文、原作者
和生产移植文件中的历史修改记录。`PINNED_UPSTREAM.md` 的“旧代理仍为生产入口”
是暂存时描述，当前生产状态以下节及源码为准。

## 已删除范围与后续删除边界

`SpiderProxyPlayback.kt`、`SpiderLoopbackProxy.kt`、`HerrinSpiderHlsProxyRewrite.kt`
已不在 main；仅在 test/androidTest 留有历史回归实现。本轮不重复删除或修改它们。
新增静态契约检查全部生产 source set 的 Java/Kotlin 定义和引用，防止从 debug、
release 或其他包重新引入。静态检查不能替代编译后的 APK 内容或行为验收。

`FongMiParsePlayback` 当前仍是生产 ParseJob 宿主，不能按旧计划名称直接删除；
只有替代宿主验证成功才可移除。`FongMiJarPlayback`、`TvBoxJsonPlayback` 等入口须先
确认调用者及用途再删。不删除独立 NAS/WebDAV、云盘、CMS 目录/选源、授权、详情选集
或遥控器功能；不修改已验收首页/TMDB UI。阶段 D 不得保留双生产解析或代理。

## 本轮证据与下一步

- 离线结构检查、43 份原版 blob 校验和生产旧链路边界检查通过。
- 新增检查器 3 项回归通过，覆盖 debug Java、异包 Kotlin、release 文件及测试隔离；
  同时修正既有 WebView 结构检查把 Bridge 注释当实现的误报，仍拒绝实际 WebView 代码。
- HEAD 的 workflow runs 查询为空、combined statuses 为空；最近分支可见成功为
  `ebd67b32e22249ff5f1c32d007e7871308646953`：Debug `41819258636`、TV Emulator
  `41819258904`，不覆盖其后 **16** 个提交。PR #80 保持 Draft，API 报 mergeable=false。
- 构建与后续状态见 [云端断点](cloud-progress.md)。没有当前提交的 APK、模拟器或 TCL
  出帧证据，故阶段验收和 P0 完成都不能宣称。

下一条开发操作：先恢复最新 HEAD 的 Debug/单元测试验证，优先处理真实失败；随后
对固定上游 ParseJob 与现有 Host 注入版本做方法级差异确认，保留已验证的取消/期限
修复。未闭合阶段 A 验证前，不推进 C/D，不再扩旧代理白名单。
