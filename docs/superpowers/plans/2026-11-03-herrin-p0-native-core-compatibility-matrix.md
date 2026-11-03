# P0 原生播放核心：现有入口与待替换依赖矩阵

基线：Herrin PR #80 commit `5d777026b09ea9f06684c033aebba02b97ca1c2a`；
上游 FongMi/TV `fongmi@305dff233cde512e2c83f71529b7c3ecfd61fefc`。

| 阶段 | 上游原生实现 | 当前 Herrin 实际生产路径 | 待迁移 |
| --- | --- | --- | --- |
| 源和站点 | `VodConfig`, `BaseLoader`, `JarLoader` | `TvBoxSpiderBridge`, `TvBoxSpiderRemote` | 站点/授权会话接入原生加载器 |
| 选集播放 | CatVod Spider `playerContent` | `TvBoxSpiderBridge.kt:558` | 原始结果直接传给解析核心 |
| 解析 | `ParseJob` JSON/WebView、多类型解析 | `FongMiParsePlayback.resolve` | 上游解析器 + UI WebView 宿主 |
| HTTP 代理 | `server/process/Proxy`, `Nano` | `SpiderProxyPlayback.normalizePlayerResponse`、`rebaseUrl` + `SpiderLoopbackProxy` | 上游响应语义、会话安全、端口绑定 |
| 播放 | `PlaySpec`, `MediaItemFactory`, `PlayerManager`, Exo | Herrin `PlaybackMedia` + 官方 Media3 | 完整构建/播放/音轨/遥控器一致性 |
| 播放入口 | FongMi 播放页 | `MainActivity.kt:1148,1229,1678` | 最后切换到单一 HerrinPlaybackAdapter |

观察到的 TCL 报错 `SOURCE_PROXY_INVALID_ROUTE / UNSUPPORTED_OPERATION` 位于播放器解码之前。因此不能凭 Media3 构建成功或模拟器 fixture 出帧断言真实 TVBox 解析成功。

## 已核实的兼容阻碍

Herrin 当前依赖 Maven Central 标准 `androidx.media3:*:1.9.4`。
FongMi 上游 `MediaItemFactory.java` 使用 `MediaItem.Builder.setAdblock`、`setImageDurationMs`，并引用 `androidx.media3.datasource.IsoUri`、`LocalClearKeyLicense` 等上游扩展；这不是直接兼容标准 Media3 的独立源文件。直接复制到 Android main source set 将破坏编译。应先厘清上游 Media3 fork/Gradle 模块和相应授权，再分别编译与验证。

## 进度真实性

本次先将原版 Loader、JarLoader、ParseJob、Proxy 放在 `android-tv/vendor/fongmi-tv`，通过独立 SHA 校验进入既有 CI 的静态检查步骤。这四份文件尚未启用。下一里程碑是带可编译依赖的原生运行时，以及脱离旧 `SpiderProxyPlayback` 的自动解码测试。只有新链路验证成功，才删除旧生产核心。

保留 Draft PR；不改首页、TMDB 展示和用户 NAS/云盘源；未经授权的外部可执行插件不运行；上游 GPL-3.0 与修改记录必须保留。
