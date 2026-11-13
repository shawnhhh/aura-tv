# Herrin P0 — 2026-11-12 定制 Media3 来源专项（批次）

分支 `codex/herrin-p0-live-playback-20261110`，起点 `1dac70d8`。
用户要求先批量开发、最后统一测试。PR #84 仍为 Draft，不触碰 main 和已验收 UI。

## 本轮关键发现

之前的独立 `fongmi-kernel` 缺少原版定制 Media3 构建材料。
核对到公开的 [webhtv/webhtv](https://github.com/webhtv/webhtv)
在固定提交 `4e30ffaf219b1db15fe5662aeff6f9820e2b32bf`
发布了 17 个 `1.11.0-alpha01-fongmi` Media3 构建文件。
**这些产物不是 FongMi/TV@305dff2 的匹配证明，也不是原版 MPV/FFmpeg 完整依赖。**
项目仍不得将其直接混入正式依赖；该 fork 的原始 API/许可证/版本关联需要逐项验证。

新增 `media3-candidate.json`，锁定了其中 3 个最具判别力的 AAR，
分别是 `media3-common`、`media3-exoplayer`、`media3-ui`，
保存真实上游分支对应仓库提交与公共 .sha256 文件核实的 SHA-256。
新增 `audit_media3_candidate.py`，只允许从该 immutable commit 下载源码仓库
已经公开发布的 AAR，检查实际 SHA-256、classes.jar 和原版必需的
`DecoderMode`、`DiscMenuController`、`SubtitleSelectionState`、
`IsoDiscPreparation` 与 `PlayerView.setBackendPlayer` 签名。
任何不兼容、无法访问或校验失败都必须保留为阻塞，不使用假 AAR 或普通 Media3。

## 上轮非内核测试失败隔离

`1dac70d8` 的 `:app:testDebugUnitTest` 完成 722 项，6 项失败：
5 项 SMB 集成依赖真实服务或连接，1 项远程媒体探测；
宿主 Kotlin/Java 编译本身未失败。该失败仍保留为未解决项，
不把它归为原版媒体内核的类路径错误，也不为本轮反复重跑 722 项。
本轮仅复检 kernel contract、宿主编译和上述原始媒体依赖候选审计；
完整 JVM/TV/ABBA/ARM 测试待原版内核真正能够编译、接管之后统一执行。

## 严格边界

定制 Media3 的候选 API 合格也**不等于**整个原版播放器能编译。
仍需原始 `lib-mpvplayer-release.aar`、`lib-decoder-ffmpeg-release.aar`
及其匹配原生组件与许可证，完成整个 5.8.7 类路径迁移。
当前没有新验收 APK、真实首帧、60 秒播放、Seek、重播或切集通过证据。
