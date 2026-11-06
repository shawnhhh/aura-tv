# Herrin P0：整体替换播放核心，禁止继续对旧链路零碎补丁

日期：2026-11-03；用户明确要求：**删掉 Herrin 当前自研播放链路，以 FongMi/TV（TVBox/CatVod 兼容实现）为播放核心**。此文档覆盖此前以 SpiderProxyPlayback/媒体探测补丁为中心的 P0 修复策略，不代表移植已经完成。

## 唯一目标与非目标

唯一验收目标：在 TCL T7L Ultra 上，以用户合法接入且授权的 TVBox/FongMi 单仓或多仓源，搜索影片→获取详情/选集→运行相应已授权 Spider/解析→代理和流媒体请求→播放器连续出帧、声音正常、遥控器可暂停/继续/选集。凡无法真实出帧，P0 未完成。

保留已验收 Herrin 首页、TMDB/IMDb 展示、收藏/历史、导航、设置、NAS/云盘接入与原有授权边界；不改 M4.70 UI、不合并 main，不扩大未经批准的插件可执行范围。**移除旧链路只针对 playback core**，不能为了“删除”先把构建破坏。旧代码在 Git 历史保留，新的编译/真机验收成功后从生产入口和源码删除。

## 已核实的参考基线

上游官方 FongMi/TV `fongmi` 分支 SHA `305dff233cde512e2c83f71529b7c3ecfd61fefc`（开始替换时锁定上游提交）。
- `app/src/main/java/com/fongmi/android/tv/api/loader/BaseLoader.java`
- `app/src/main/java/com/fongmi/android/tv/api/loader/JarLoader.java`
- `app/src/main/java/com/fongmi/android/tv/player/parse/ParseJob.java`
- `app/src/main/java/com/fongmi/android/tv/server/process/Proxy.java`
- `app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java`
- `app/src/main/java/com/fongmi/android/tv/player/media/`
- `app/src/main/java/com/fongmi/android/tv/player/engine/`
- `app/src/main/java/com/fongmi/android/tv/player/exo/`
- 按依赖矩阵补齐 `bean/Result`、`api/config`、`player/parse`、`MediaItemFactory`、`PlaySpec`、`WebView` 解析与网络会话等实际调用链，禁止改写成空壳 stub；所需 Media3 依赖/自定义 fork 逐项明确，而不是假设原生 Media3 可直接替换。

之前 Herrin 只分段移植了 CatVod SDK/部分 loader、Proxy、Parse；并非上游整条播放路径一致。现有 `SpiderProxyPlayback.kt` 代理重绑定和 `SpiderLoopbackProxy.kt` 二次 HTTP 服务、`FongMiParsePlayback` 适配与 `MainActivity` 多级探测之间存在额外语义差异。错误 `SOURCE_PROXY_INVALID_ROUTE / UNSUPPORTED_OPERATION` 落在 Spider URL 归一化/代理路由阶段，不是已经发生解码失败。**禁止再猜测扩容 `do=` 白名单作为核心策略。**

## 任务顺序与交付标准（逐项 TDD）

1. **冻结兼容矩阵**：记录上游完整调用图以及 Herrin 当前入口/出口和要删代码；区分 upstream 原版、必要 Android/进程/授权桥接、Herrin UI Adapter。列出缺失依赖、Java JAR/JS/Python/node 的实际运行时覆盖范围；未经用户批准不可自动加载可执行插件；GPL-3.0 上游版权、源码与修改记录保留。编写对旧路径禁止再触发的静态测试。
2. **独立移植完整 FongMi 播放模块**：将有真实行为的 loader、playerContent、ParseJob（包括 JSON/WebView 解析）、Proxy 请求/响应、媒体创建、Exo 播放模块及必要依赖引入隔离 `tvbox-playback` 命名空间或 Gradle module。尽量保持上游可比对源码，差异限于 Android 宿主、进程隔离、权限与 UI 桥。禁止调用旧 `SpiderProxyPlayback.rebaseUrl/normalizePlayerResponse` 进行二次解释。
3. **仅建立一个最薄的 HerrinPlaybackAdapter**：入参为选中来源、影片 ID、线路/集数、授权会话；出参为上游 PlayerManager 真实播放事件/帧/错误，供现有详情页和播放器容器展示；NAS/云盘直链与 CMS 来源在源层适配，不对 Spider/解析底层再写多套代理逻辑。
4. **移除旧生产路径**：切换 `MainActivity` 播放入口与已验收详情选集到新 Adapter；不在新旧两套播放路径中双重解析、双重代理。确认新链路测试通过后删除旧 `SpiderProxyPlayback`、旧 `SpiderLoopbackProxy`、`FongMiParsePlayback` 及被新模块替代的选源/探测逻辑中仅供旧链路使用部分；仍为 NAS、云盘或非播放功能所需的独立逻辑须保留。
5. **自动化验收**：同一构建由真实上游 CatVod DEX fixture 的选集→播放请求→代理 HTTP 实体（不只是 URL）→MP4/HLS/TS→Media3 解码帧；覆盖单仓/多仓、跨站点、播放地址变更、请求头/Range/跳转、401/403、失效源自动退避、播放器全屏遥控器。所有关键测试强制运行，禁止 skip/删除测试求绿；Android Debug + Android TV Emulator 必须有当前 HEAD 的 CI 成功记录。
6. **TCL 真机验收**：安装当前 SHA 对应 APK，复测同一失败影片/同一已获授权配置，记录安全脱敏日志及视频真实首帧/音轨成功。若未成功，不得标 P0 完成，不得说“TVBox 已移植完成”。对暂不支持的外部脚本运行时明示兼容差距，不可静默跳过。

## 控制与回滚

始终使用 PR #80 的现有头分支 `codex/m471-tmdb-personal-to-tvbox-playback-20261102`，保持 Draft；不合并 PR #79/#80/main。开始任何提交前读取最新 HEAD，检查并发提交；每一阶段有明确编译和测试证明。移植中若上游 GPL、依赖、二进制授权或 build 阻塞，提交可复现日志及最小阻塞点，不能回到无止境新增旧代理操作补丁。

## 当前状态（创建本计划时）

已核实上游完整 FongMi 文件结构与现有 Herrin PR #80，**尚未完成原生模块移植，也未删除旧播放链路、未生成本次整体替换的可用 APK**。下一项可验证产出是兼容矩阵+可编译的上游模块+来自真实播放器的失败/出帧自动测试，而非另一个 `UNSUPPORTED_OPERATION` 白名单补丁。
