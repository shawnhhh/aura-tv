# Herrin P0 — MPV/FFmpeg 原生库批量开发与真实构建定位（2026-11-12）

起点：`9794e891`；独立分支：`codex/herrin-p0-live-playback-20261110`。
保留 Draft PR #84，不合并 main，暂不改动原版 TV 源或已验收 Herrin UI。

## 上次真实失败根因

GitHub Actions `43146074390` 的 `Gradle 9.1.0` 真正执行了 153 个构建任务且
`BUILD SUCCESSFUL`。MPV AAR 存在，类数 174，含 arm64-v8a / armeabi-v7a。
**旧流水线失败在产物收集和验证，不在 Java/Native 构建**：FFmpeg 输出没有在原来的
`vendor-media/libraries/decoder_ffmpeg` 目录中找到；校验器同时用下划线匹配
实际 Gradle 模块的 `lib-decoder-ffmpeg-release.aar` 连字符名称。
旧结论“FFmpeg 编译失败”不准确。

## 本批实质改动

1. 新增 `collect_native_candidate.py`：扫描固定 `FongMi/media@3c2cbe8`
   checkout 的真实 Gradle `outputs/aar`（支持模块内或 buildout 布局），
   **只接纳两个完全匹配的 release 产物**；重复、缺失都拒绝，不改写/重编 AAR。
2. `verify_native_candidate.py`：明确 `lib-mpvplayer-release.aar` 和
   `lib-decoder-ffmpeg-release.aar` 的名称，解析各 ABI 的真实 ELF
   class + e_machine（AArch64/ARM），核查 Java 类、`libmpv/libplayer` 和
   FFmpeg native 文件，输出 SHA-256。
3. 新增 `--upstream`：扫描固定上游 FongMi/TV 真正的
   `androidx.media3.mpvplayer` / `androidx.media3.decoder.ffmpeg` imports，
   比较源码产出的 AAR Java API；若缺类，即使 Gradle/AAR 成功也标为
   `SOURCE_BUILT_NATIVE_CANDIDATE_TV_API_INCOMPATIBLE`，**禁止当作 P0 内核依赖**。
4. 新增零网络 unittest（仅小型测试 ZIP/ELF 夹具，并非真实原生库）。
5. 本批最后一次性更新源构建 workflow：先执行夹具测试，Gradle 9.1.0 编译
   原版媒体源，收集全树产物、核验原始 TV API，提供摘要和 5 天候选 artifact。
   即使因 API 不匹配失败，也保留原样候选供排查；**不会自动编入 Herrin APK**。

## 未完成

原版 TV 5.8.7 的定制 Media3 还有独立缺口：
`DecoderMode`、`DiscMenuController`、`IsoDiscPreparation`、
`SubtitleSelectionState`、`PlayerView.setBackendPlayer` 等。
已知 `webhtv/webhtv@4e30ffaf` 的预编译 Media3 候选**不兼容**；
不会以“从源码构建成功”的 FFmpeg/MPV AAR 替代这一证明。

本流程通过，只能说明真实原生 AAR 打包成功且 ABI检查达到声明范围。
它不能证明完整 `fongmi-kernel:core/adapter` 编译、真实视频首帧、
《小城良方》奶酪/玩偶哥哥播放、 seek 或授权有效。

## 本批最终 CI 证据（43152499210）

- 5 个小型单元测试通过；原版 FongMi/media 固定源 Gradle 9.1.0 进行 153 个任务，`BUILD SUCCESSFUL`。
- 真实产物：`libraries/mpvplayer/buildout/outputs/aar/lib-mpvplayer-release.aar`（SHA-256 `915579744201054d0079ff3a4661c18804febe0683208854bb837be8d5e4518d`），`libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar`（SHA-256 `3efae9f489cd936d2aea4be1dede874b731d68617a49ca42f3c2ab229b52effb`）。
- ARM32/ARM64 ELF 和 Java 打包检查无错误。上游 TV 实际 11 个所需原生 Java imports 中 **4 个缺失**：`MpvConfigFileInspector`、`MpvDecoderMode`、`MpvSubtitleContentLoader`、`MpvDrmSupport`。故本次 CI 为预期的 API 不兼容失败，**不是编译失败**。
- 候选 AAR 已由 CI 上传供进一步兼容性审查，**不集成至 Herrin APK**。本候选锁定为明确不兼容，避免再次自动运行约四分钟的已知无效编译，仍可手动触发源构建。
