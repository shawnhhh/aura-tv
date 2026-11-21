# FongMi 整体内核移植：第一阶段检查点

2026-11-11。**第一阶段未通过，整体移植未完成，P0 未完成。**
开发起点 `d7ad546f2c5cce23f5e53e2d07bfe2e00d8522b4`，分支
`codex/herrin-p0-live-playback-20261110`。集成目标为 Draft PR #84；其实际
head 是 `codex/herrin-global-search-20261107`，不能把本分支已推送说成已进入 PR。
按用户要求串行：未建立可播放上游基准前，不把源码存档宣称为第二阶段完成。
本记录取代继续围绕旧内核打补丁的方向；未使用 Superpowers。

## 完整源码与许可证

完整上游仓库已获取。新增 `android-tv/vendor/fongmi-core/upstream` Git submodule，
固定 `305dff233cde512e2c83f71529b7c3ecfd61fefc`，保留原目录、资源、构建脚本、
CatVod、运行时和 LICENSE.md，共 1360 个受控文件；不是只复制播放器类。
此提交与既有 43 文件存档的固定版本一致，app 声明 5.8.7；它是**候选基准**，
尚无该源码构建与官方发行 APK 一一对应的证明，亦无本环境可播放证明。
本次还观察到上游分支 HEAD `5b41d9da52e1573ab9b5d399d656d11eb9fe8557`，
不随移动分支自动升级，不混用两版本源码。

`baseline.lock.json` 固定 commit、tree 和许可证 SHA-256；`upstream-files.json`
逐文件列出 Git blob。`audit_fongmi_baseline.py` 对完整树、实际工作区字节、
未提交变化和许可证进行验证。其通过只证明来源完整，不证明已编译或出帧。

上游 LICENSE.md 为 GPL-3.0；保留原作者版权及所有模块声明。
衍生分发需提供兼容许可、对应完整源码及必要构建材料。外部二进制的来源和
许可证需独立核实，不能靠根目录 GPL 声明推定已获授权。

## 原版实际链路与当前执行差异

下列上游路径均相对于完整 checkout 的 `app/src/main/java/com/fongmi/android/tv/`。
当前路径相对于 `android-tv/app/src/main/java/`。

| 环节 | 原版真实代码/行为 | 当前 Herrin 执行路径/差异 |
| --- | --- | --- |
| 仓加载 | `api/config/VodConfig.java`、`BaseConfig.java` 管理配置、站点、全局解析/flags | `tv/herrin/app/TvBoxWarehouses.kt` 独立解码和配置模型；原 VodConfig 不在 sourceSet |
| Spider 初始化与生命周期 | `api/loader/BaseLoader.java` → `JarLoader`/`SpiderLoader`；JAR缓存、Init、recent、siteKey、Net/Local；clear/destroy 管理退役 loader | `TvBoxSpiderBridge.kt` 的 `SpiderJarFiles`、`TvBoxSpiderWorker` 自行下载、Dex加载、会话、初始化、资源释放；`TvBoxSpiderRemote.kt` 额外 Messenger/恢复流程 |
| CatVod/运行时 | 独立 `catvod` 模块；BaseLoader 按 csp/node/js/py 路由到原 loader，RuntimeModule 按模块要求加载 | CatVod 源码编入 app，并有主机适配；`FongMiScriptRuntimes.kt`/HTTP会话宿主等自定义运行环境，不能假定 ABI/全局状态等价 |
| 搜索 | `model/SiteViewModel.java` → `api/SiteApi.searchContent` → `site.recent().spider()`，SearchTask/runner 调度 | `TvBoxSpiderWorker.search` → `spider.searchContent` → 自写 `SpiderVodCodec`；上层 `HerrinSourceRouter.kt` 另有预算/过滤 |
| 详情/选集 | `SiteApi.detailContent` → 原 Result/Vod/Flag/Episode → `playback/vod/VodPlaybackController`、State、Request | `SpiderVodCodec.detail` 转成 Herrin Film/episode/line，签名归属由自写 authority 管理；无原 VodPlaybackController |
| playerContent | `SiteApi.playerContent/loadPlayerContent/resolvePlayerContent`；先停 Source，原 flags、选集与 Source.fetch 处理 | `TvBoxSpiderWorker.play` 直接调用 Spider，随后 `NativeFongMiRoute.playerContent`、转换媒体模型；未经过原 SiteApi/Source 调度 |
| 解析调度 | 原 `PlayerManager.setMediaSource` → `player/parse/ParseJob.java`，与 VodConfig、WebView、JarLoader 上下文联动 | `FongMiParsePlayback.kt` → `tv/herrin/app/fongmi/parse/ParseJob.java`（提取适配版本）；`FongMiJarPlayback`、Browser/Parse/PlaybackRouter 再参与 |
| Proxy | `server/Nano.java` → `server/process/Proxy.java` → `BaseLoader.proxy`；siteKey/js/py/recent 路由；Object[]/Response 转 chunked response | `nativeplayback/NativeFongMiProxy.java` 独立 Nano、私有端口和归属；`NativeFongMiRoute.java` 改写本机地址。原 Proxy 不在实际构建路径 |
| HLS/媒体请求 | 原 Proxy.createResponse 不预读并重写 HLS正文；原 ExoMediaSourceFactory/ExoUtil/引擎负责媒体传输 | NativeFongMiProxy 读取 HLS、识别单URL引用、拉取、256KiB限制、NativeFongMiHls 重写；FongMiMediaProbe/HerrinRemoteMediaProbe/MediaProbePrefix 是额外前置路径。已确认行为不同，未证明它就是403根因 |
| 播放器、进度、状态 | 原 `PlayerManager.java` → PlayerEngineFactory → ExoPlayerEngine/MpvPlayerEngine、PlaySpec、预载/超时/音轨字幕/释放 | `HerrinPlaybackAdapter` → **重写的 Kotlin** `com/fongmi/android/tv/player/PlayerManager.kt` → `FongMiExoPlayerFactory` → stock Media3 1.9.4；同包同名不代表原代码 |
| 解码出帧 | 原定制 Media3/MPV/JNI/系统解码器与原 engine 生命周期协作 | stock Media3/系统解码器；原定制API与依赖未齐。既有夹具首帧不证明真实节点首帧 |

既有 `vendor/fongmi-tv` 的 43 个精确文件未作为完整模块加入 Gradle；
app/build.gradle.kts 只额外编入 Sardine vendor sourceSet。不能再以
`verify_fongmi_vendor.py` 的 43 项通过声称播放核心完整。

## 依赖和可复现构建缺口

完整版本表、Maven 坐标、bundle、仓库内 AAR 的 SHA-256 和缺失项见
`evidence/20261111-fongmi-build-inputs.json`。

- 上游 AGP 9.4.0、Gradle 9.7.1、Java 21、compile/target SDK37、minSDK24；
  当前 Herrin Gradle8.9/Java17目标/SDK35/minSDK26，需要正式选择构建迁移边界。
- 上游依赖 catvod、chaquo、nodejs、quickjs、thunder，动态 runtime 另有 Python/MPV/BDJ 等。
  app/libs 通配 AAR 不足以构成依赖锁。
- 本次检出存在 hook-release.aar、tvbus-release.aar、quickjs runtime-release.aar，
  **未完成其独立许可证/对应源码核实**。不能把“文件存在”当合法可分发证明。
- 构建脚本明确引用而 checkout 缺失：`app/libs/lib-mpvplayer-release.aar`、
  `app/libs/lib-decoder-ffmpeg-release.aar`。
- 原 PlayerManager 引用 DecoderMode、DiscMenuController、SubtitleSelectionState、
  IsoDiscPreparation 等定制 Media3 API；其对应库/源码来源还未核实。
  不允许用桩类、删分支或 stock Media3 重写去冒充整体移植。
- 原 app/build.gradle 强制读取 local.properties，debug 也引用 release 签名配置。
  不能伪装官方包名/签名以满足 Spider；合法测试签名与授权差异必须记录。

## 原版与 Herrin 的真实证据

目标固定：`http://www.饭太硬.net/tv`，《小城良方》（2026），奶酪、玩偶哥哥。

已读取而没有重跑的官方参考 [run42863215516](https://github.com/shawnhhh/aura-tv/actions/runs/42863215516)，
job129994561737。官方5.8.7原包 SHA-256 为
`f633039fc94bac9897c5933c61a18aabfde3c1e95ab16b26796c84b9a35439d9`，
未修改/重签。**工作流 success 的正文却是 REFERENCE_BLOCKED**，
failure_stage=open_settings，failure_code=REFERENCE_UI_TARGET_NOT_REACHED，
targets=[]，范围 SEARCH_UI_ONLY_NO_MEDIA。原始JSON摘录保存在
`evidence/20261111-official-reference-existing-run.json`。
这不是源端403或登录阻塞，也不是原版成功；不能沿用“原版已在同机播放”的结论。

当前本机一次仓库可达性实测：2026-11-11T01:11:21Z，经环境继承代理，
HTTP403、body=`Domain forbidden`、server=envoy。状态报告为 restricted/enforced，
饭太硬域名未允许。**这是测试环境网络阻塞，不是媒体CDN403证据。**
未读取网盘凭据、未绕过登录/DRM/访问控制。

| 本轮实际结果 | 奶酪 | 玩偶哥哥 |
| --- | --- | --- |
| 原版同仓/同片/同节点对照 | 未执行到，基准未建立 | 未执行到，基准未建立 |
| Herrin 本轮搜索→详情→选集 | 因仓库域名阻塞，未执行 | 因仓库域名阻塞，未执行 |
| 真实QR/合法授权 | 未观察到 | 未观察到，不能标“等待扫码”或“授权通过” |
| 首帧/60秒/Seek/第1集重播/第2集 | 全部未验证 | 全部未验证 |

11月10日旧内核的奶酪解析成功后本机502/远端403、玩偶搜索空响应，仍是历史失败，
详见旧检查点。它们不能用来验收新核心，也不能排除移植差异。

## 本轮文件、删除与验证

新增：完整上游 submodule、固定锁、1360文件清单、来源审计脚本、依赖清单、
原版历史报告摘录和本检查点。**整体内核编译集成文件：尚无；旧播放代码删除：0。**
候选待替换路径列于上表，需先完成原版基准，再整块导入，真实测试后删除；
不能现在删除旧代码然后宣称新核心已接管。

已停止 `herrin-p0-real-playback.yml` 对本开发分支 push 自动运行旧内核修补测试；
保留明确命名的手动历史诊断入口，避免每次源码存档变化重复执行无变化的节点请求。
没有新增失败回退路径，没有修改 Apple TV UI、TMDB、NAS优先、用户源顺序、直播或遥控。

- 完整上游1360文件来源与工作区字节审计通过。
- 本地 `GRADLE_USER_HOME=/workspace/.gradle-herrin ./gradlew :app:assembleDebug`
  已实际尝试，Gradle下载阶段抛 `java.net.SocketException: Operation not permitted`，
  尚未进入 Android 编译；本机也未发现 SDK/adb/emulator 或 /dev/kvm。
- 本轮未运行完整 JVM/TV/性能 CI，遵守真实节点门槛；历史CI不算本HEAD通过。
- 未生成、交付验收 APK，未合并主分支，PR仍须保持Draft。

## 串行续跑与额度检查点

当前停在阶段1：可播放上游commit、完整依赖合法来源、同机真实基准尚未完成。
本环境已提出补齐仓库网络允许及 Android 测试环境的请求；不要循环重试403、
重复跑ARM canary、48源导入或无变化旧内核测试。可见工具没有 Codex 额度查询接口，
额度余额为**未知**，没有声称已检查到可用额度。

下一次先读取本记录、git HEAD和已有证据，运行来源审计一次。补齐测试环境后，
先用原包完成两节点逐阶段对照，记录设备/ABI/网络/版本/授权状态及失败点，
并确认该可播原包的源码commit和定制依赖。若原版也失败，记录实际来源错误，
不能把设置页脚本错误归为外部授权阻塞。

阶段2的边界已明确：整体编译原 app共享播放依赖、catvod及必需runtime，
保留原包结构和调用；适配层只接入站点/影片/选集及状态，不含解析/代理/HLS。
阶段3仅在新核心真实验证后切断并删除旧路径，不保留隐式回退。
阶段4需奶酪29集第1集首帧/60秒/Seek/重播/第2集及玩偶合法网盘流程；
只有真实授权QR可见才标等待授权。阶段5按用户门槛再做同HEAD完整回归及合格APK。
