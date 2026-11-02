# M4.67 播放引擎移植记录（Agent 3）

**结论：当前为可编译接口的部分源码移植，尚未达到完整 FongMi 播放引擎验收。绝不将本提交称为完整 mpv/FFmpeg/libass 或 TCL 第三方片源通过。**

- 独立分支 `codex/m467-player-engine`，worktree `/workspace/herrin-m467-engine`。
- 宿主基线 `cd6deb3d116969f36e451be6ba6dd8649dc4b23f`。
- 直接源码基准：`FongMi/TV` 的 `fongmi@5e191aaced194e5cac5668cc91908ecb65336222`。
- 依赖核查：`FongMi/media` 的公开定制分支 `release-1.11.0-fongmi@3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d`；`FongMi/mpv-android` 的 `fongmi@049ade89abc85fc05ef73dc9ba6f1810466c9f8a`。默认 `media/release` 是较新的普通 Media3 镜像，不能误认为当前 TV 的定制源码。

## 实际代码

| 上游文件 / 方法 | Herrin | 状态 / 自动化 |
|---|---|---|
| `player/media/ConcatenatingUrl.java` 全部方法 | 保留原包、原文件 | 原样导入；带时长分段协议、UTF-8 EDL 字节长度、非法分段的 JVM 测试 |
| `player/exo/ExoMediaSourceFactory.java#createMediaSource` | 同包同类 | 保留分段解析和 `ConcatenatingMediaSource2` 创建；宿主注入每会话 DataSource，`UriUtil.uri` 适配为 Android Uri |
| 同类 `getSupportedTypes` | 同包同方法 | 原始委托语义 |
| 同类 `createDefaultExtractorsFactory` | 同包同方法 | 保留 TS 时间戳搜索窗口 ×10；定制 Matroska HTTP 延迟 cues 标志缺失，不能宣称已移植 |
| `player/track/TrackUtil.java` 全部方法 | 保留原包、原文件 | 只将 Android TextUtils 空判断换成同语义 Java 空判断，真实 `PlaybackTransport.cycle` 已调用原来的格式 / 标签 / 语言 / 顺序轨道恢复算法 |
| `bean/Track.java` | 同包值对象 | 字段、访问器、选择状态保留；移除上游 Room 注解及静态数据库入口，未假造上游数据库；宿主资料和收藏未改 |
| `ExoUtil#buildLoadControl/buildTrackSelector/buildPlayer/buildRenderersFactory` | `FongMiExoPlayerFactory.create` | 对照源码移植公开 ABI 支持的 streaming/local buffer、最高支持码率、语言、AAC、tunneling、预热、audio focus/noisy、headers；DecoderManager / 特制 renderer 部分阻断 |
| `MediaItemFactory#from/buildSubConfig/buildRequestMetadata/toBundle`、`TrackUtil#getSubtitleMimeType` | `FongMiExoPlayerFactory.mediaItem` | 宿主播放模型接口适配：外部字幕稳定 ID、SRT/ASS/SSA/VTT/TTML MIME、headers。宿主不具备上游显式字幕 flags、DRM/ISO 模型，未虚称移植这些能力 |

`player-method-map.tsv` 是上述 **11 个上游类的全部 205 个方法/构造器声明** 的逐方法清单，含上游行号、宿主文件、状态及对应测试。`InventoryPlayerMethods.java` 使用 JDK 的 Java AST，不靠文本命中猜测覆盖率。`scripts/m467/build-player-map.py` 可重生成机器可读 TSV；已用 CSV 解析校验全部 206 行（含表头）每行都是 7 列真实 TAB。205 行中 162 为 BLOCKED、15 为 PARTIAL，不能计作完成；另有 5 项 MINIMAL_HOST_ABI_ADAPTATION、23 项 DIRECT_SOURCE / 仅空判断适配。`BLOCKED` 或 `PARTIAL` 行不算完成。无法运行的方法明确写 `NOT_EXECUTABLE_CURRENT_DEPENDENCY_SET`。

UI 连接由恢复 Agent 修改播放控制构造，不变更布局或影片资料。新增工厂不会设置 ClearKey、不会伪造 license/DRM provider；委托合法平台 DRM 验证。每会话 headers 不进入全局 URL-only 缓存，保留 Herrin 的凭据隔离。当前真实平台 codec fallback 被保留；它不是 FFmpeg 软件解码，不能拿来掩盖代理错误。

## 原生及定制 Media3 的实证阻碍

`native-audit.json` 由 `scripts/m467/audit-native-dependencies.py` 只读核查并记录 SHA、ELF 架构、DT_NEEDED、SHA256；退出码 `2` 明确表示 **BLOCKED**：

1. TV `app/libs` 未提供 `lib-mpvplayer-release.aar`、`lib-decoder-ffmpeg-release.aar`。仓库通过 `lib-*.aar` 忽略全部这些输入，不能直接复现当前构建。
2. 公开 `media` 定制分支（2026-08-12）的源码不存在 TV 当前使用的 `DecoderManager`、`LibassPlaybackSession`、`LibassSubtitleController`、`ContentTypeResolvingMediaSource`、`SubtitleTranscriptSession`、`ProgressiveIsoCache`、`SecondaryTextTrackSelector`。其中 `DiskPreloadManager` 在公开分支中存在；其余上述 7 个必要 API 缺失。因此直接复制当前 `ExoPlayerSession`、`ExoSubtitleController`、`MpvPlayerEngine` 等会产生真实编译错误。没有新增假类去填平缺失 ABI。
3. 公开分支附带的 arm32/arm64 `.so` 的 DT_NEEDED 包含分离 `libavcodec.so` 等；当前 TV 的 `MpvNativeLibraries.groovy` 明确拒绝这些 split FFmpeg，要求统一 `libffmpeg.so`。把旧二进制凑进新 Java 层不能通过上游自己的 ABI 核查。旧 `.so` 没有进入本 PR。
4. 真正执行 `/workspace/fongmi-mpv-android/buildscripts/buildall.sh --arch arm64 mpv`，退出码 `1`、完整日志 `/workspace/logs/m467-native-build-arm64.log`：`Can't find toolchain inside NDK`。当前构建脚本固定 NDK `r30/30.0.16248370`、Android API24，环境没有该 NDK。安装 NDK 仍不能解决第 1–3 项 Java/ABI 版本不匹配。

可验证继续路径：取得 **与当前 TV SHA 同期** 的 `media` 源码/AAR及对应 native 构建依赖清单；在 NDK30/API24 对 arm64-v8a、armeabi-v7a 构建 unified FFmpeg/mpv/JNI，并运行 TV `MpvNativeLibraries` 检查及签名运行时元数据验证。官方 Release/module 的独立 mpv APK 存在，但本任务没有把其签名/宿主哈希未经验证的运行时当成已可用库。

## 许可证

| 内容 | 许可 / 归属 | 本提交处理 |
|---|---|---|
| 上述 FongMi/TV Java 及适配代码 | FongMi / GPL-3.0 | 保留包名、源 SHA、文件归属和修改说明；使用已有完整 `android-tv/LICENSE-GPL-3.0.md` |
| 标准 Media3 1.9.4 | Android Open Source Project / Apache-2.0 | 保持已存在的官方 Maven 依赖；无私有 fork 冒名 |
| `FongMi/media` Java | 文件头 Android Open Source Project / Apache-2.0 | 仅审计，无该仓库二进制再分发 |
| `mpv-android` JNI 宿主 | Ilya Zhuravlev / sfan5 / MIT | 仅审计；不把 MIT 宿主许可当成 libmpv/FFmpeg 的许可 |
| libmpv、FFmpeg、libass 及其依赖 | 独立许可；当前 FFmpeg 构建启用 GPL/version3 | 当前未打包；后续必须附匹配源码、依赖许可和构建选项，再做再分发核查 |
| 新测试片与字幕 | Herrin 原创合成 fixture | 只使用仓库已有 `synthetic.mp4` 重封装；无第三方片源或凭据 |

## 编译与测试记录

- 已实际运行 `scripts/m467/generate-engine-fixtures.sh`，FFmpeg 重封装 MKV/HLS/DASH 并 ffprobe 检查 MP4、MKV、HLS master/child、DASH；H.264/AAC 正常，原 MKV 保留双 AAC 音轨，日志 `/workspace/logs/m467-engine-fixtures.log`。
- 新增 JVM 3 例：精确分段/时长/UTF8、非法分段、全部字幕 MIME。
- 新增 Android 仪器 3 例 `M467FongMiTrackRestoreTest`：真实 Media3 轨道模型在 manifest ID/ordinal 变化后的语言/标签恢复、缺失语言待恢复及关闭状态、secondary role/格式往返。
- 新增 Android 仪器 8 例 `M467EngineContainerTest`：真实 HTTP MP4/MKV 双音轨/HLS 主子列表分段/DASH init+fragment → 实际 Media3 输出帧；SRT/ASS/SSA/VTT 实际 cue。ASS/SSA 只声称文字 cue，未声称 libass 特效。
- 首轮 Gradle 命令：`:app:testDebugUnitTest --tests tv.herrin.app.M467FongMiMediaContractTest :app:compileDebugAndroidTestKotlin`。依赖解析被基线 `com.github.thegrizzlylabs:sardine-android:0.9` 的 `jitpack.io` HTTP403 阻断；没有执行 JVM 或模拟器测试。完整日志 `/workspace/logs/m467-engine-gradle.log`。后续运行结果必须追加，不允许把编译通过计为仪器通过。
- 尚无 mpv/FFmpeg fallback、libass、预加载/decoder manager、4K/HDR 真机和 TCL 第三方源通过证据。不得将模拟器上的合成源输出帧扩大为这些结论。

## 2026-11-02 播放资料与字幕部分补齐（真实接入，仍非完整版）

- 新增 `FongMiPlaybackItemSupport.kt`（沿 FongMi upstream MediaItemFactory/ExoUtil 改造），已在 `FongMiExoPlayerFactory.mediaItem` 实际使用字幕语言优先算法：多条字幕优先 `zh/zho/chi`，否则第一条，单条即默认；在 Media3 Factory 中实际使用从当前会话 `MediaItem.requestMetadata.extras` 提取的 HTTP 请求头，不建立全局静态凭据缓存。
- 补充 5 个 JVM 字幕排序/空语言/单轨/语言参数测试；补充 2 个 Android 仪器测试，用真实 Media3 RequestMetadata 和 Android Bundle 测试会话请求头隔离、非字符串字段过滤及空值。
- 上游 `ExoUtil.extractHeaders` 成为可运行的 MINIMAL_HOST_ABI_ADAPTATION；`MediaItemFactory.buildSubtitleConfigs/findPreferredSubtitleIndex/SubtitleFlags.create/get` 作为 **PARTIAL** 记录：宿主 `PlaybackMedia.subtitles` 缺失上游 `Sub.rawFlag`，无法支持显式用户选择标志。`MediaItemFactory.hasExplicitFlags` 仍受阻，绝不计入已完成。
- 这 5 项改动使 205 项总清单中 BLOCKED 从 167 降至 162；PARTIAL 从 11 升至 15，不等于 5 项完全移植。**7 个定制 Media3 API 与两个 native AAR 仍缺失；mpv/FFmpeg/libass/4K HDR/杜比真机仍不宣称可用。**
- 保持 UI、TMDB、影片资料、所有其它播放域行为和 Draft PR 状态不变；CI 总验收以本轮最新 HEAD 的真实结果为准。
