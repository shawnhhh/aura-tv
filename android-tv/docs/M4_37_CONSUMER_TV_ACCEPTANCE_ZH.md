# HERRIN M4.37 消费级 Android TV 总集成验收报告

## 交付与基线

- 唯一总集成 Draft PR：[#44](https://github.com/shawnhhh/aura-tv/pull/44)。不合并。
- 集成分支：`integration/herrin-m437-consumer-tv-20261029`。
- 基线：[PR #43](https://github.com/shawnhhh/aura-tv/pull/43) 最新 HEAD `2509da39a83bdca0b5cfc1fe2ac3a37e67b3d9da`；检查时 Debug 成功、Emulator 失败。
- 四个隔离 worktree 从同一基线建立：A `feat/m437-player`，B `feat/m437-auth`，C `feat/m437-danmaku`，D `feat/m437-metadata`。每个工作流独立提交，最后通过 merge 提交集成，无子任务 PR。
- Android 版本 `0.4.37-alpha` / versionCode `42`。原 PR、main/master 和根目录设计原型保持原样。

## 实现与证据对应

| 验收项 | 实现及自动化证据 |
| --- | --- |
| 快速导入，不进行 1/12 扫描 | 保留 M4.36 的配置导入与用户手工选站；导入不调用逐站 Spider 扫描。原 `HerrinUiSmokeTest` 快速导入/选站断言全部保留。TMDB 只在列表返回后异步处理当前列表，不阻塞导入。 |
| 点击即全屏 | 保留播放优先路由，Spider 详情和解析在全屏简单加载层执行。无插件技术过渡页；失败仅给重试/返回选集。 |
| 遥控器 | 修复根预览处理器吞掉子控件 OK 的错误；播放器根焦点左右 ±10 秒，确定/下键显示且聚焦控制栏，下键进入线路选择，按钮内左右正常移动。播放时空闲八秒隐藏，暂停保留。 |
| 真实进度 | 原创 36 秒视频 `synthetic-remote.mp4`；真实 `sendKeyDownUpSync` 注入，暂停在 12 秒，RIGHT 后 22 秒，LEFT 后 12 秒，屏幕时间标签同步。测试不替换为鼠标点击。 |
| 视频出帧 | 原创 MP4、HLS、DASH；Media3 输出帧计数与 Android compositor 截图中央区域的亮度、色彩数量联合断言。截图不能只靠 UI 按钮或 decoder READY 判定成功。 |
| 真实画质 | Media3 decoded dimensions、Format 帧率和色彩信息；测试检查原创视频 `320×180 / 24 fps`。HDR/Dolby Vision 只标识片源信息，附输出待确认；未知不推测，不从片名声称 4K/HDR10+ 输出。 |
| 线路、选集、续播 | 全屏控制栏入口打开原始来源线路/集数弹窗，选择后直接切换媒体；同集切线路带入已保存进度，各集保留独立播放历史。元数据不会修改播放 ID/URL。 |
| 扫码授权 | `Runtime.setDialog` → 同应用 Messenger → `SpiderAuthorizationHost`。支持明确 qrcode/image/buttons/onAction 协议、状态、取消、Back 和120秒超时。原 SDK callback 回插件线程，合法授权值由插件处理；主 UI 不提取凭据。独立进程 MockWebServer 测试模拟成功与取消。 |
| 弹幕 | 默认关闭；只适配明确 `Result.danmaku` URL/数组和 XML `d[p]` 协议。启用授权限定当前 origin；无全局未知服务。透明度/速度/字号由遥控按钮设置；位置只取 Media3 时钟，暂停、seek、换媒体均同步，加载错误返回空弹幕。 |
| TMDB 刮削 | 名称清洗、年份和类型识别、保守匹配同名片；API Token 密码输入。官方海报/背景/简介/评分/演职员替换展示，失败保留来源图片。缓存、增量、强制重刮和人工类型/数字ID纠错。原创 HTTP mock 检查 Bearer header、缓存和播放身份不变。 |

工作流实现提交：A `0ad5ca8`、`5593b9f`；B `c9d0e8e`；C `8c5badb`、`0978901`；D `12bc632`。后续集成修复及精确构建 SHA 以 PR commit 列表和 CI 自动报告为准。

## 测试与 APK 获取

PR 的 Checks 中必须同时检查 **HERRIN Android Debug** 与 **HERRIN Android TV Emulator** 的最新提交结果。不能把上一次提交的绿色结果当成本次结果。

- Debug：静态 XML/manifest/wrapper/native-only 检查，全部 JVM、Lint、Debug APK 构建。
- Emulator：Android TV API30 x86 / tv_1080p，真实 Media3 解码、真实遥控按键、所有原 instrumentation 测试和新增回归。无删除旧测试或降低既有断言。
- 每个 CI 作业执行 `scripts/acceptance_report.py`，产物 `build/acceptance/验收报告.md` 自动写入该次实际 SHA、CI 链接、JUnit 总数/失败/错误/跳过及 APK SHA-256。某个作业未执行的测试组显示0，需要联合两个工作流读取。
- 从 Debug 的 Artifacts 下载 **HERRIN-debug-apk**，解压 `app-debug.apk`，使用 Android TV 文件管理器或 `adb install -r app-debug.apk` 安装。Debug 签名仅供开发验收；与其他签名安装包可能无法直接覆盖。
- **HERRIN-test-reports** 含 JVM/Lint/中文报告；**HERRIN-emulator-evidence** 含测试 XML/HTML、summary.json、日志、中文报告及原创视频画面截图。Artifact 保留14天。
- 截图路径 `build/emulator-evidence/test-evidence/m437-*-visible-frame.png`。授权弹窗为安全窗口，私人二维码不得以截图验收；以 mock 的 UI/IPC 测试断言验收。

本地已运行静态检查、diff 检查并实际尝试完整 Gradle 构建。SDK35/构建工具已配置；本机无 `/dev/kvm`，且网络策略拒绝基线已有 `jitpack.io` 的 sardine-android 依赖。本地受阻不等于构建通过或代码失败；Android 完整结果由 GitHub Actions 实际运行确定。最终状态、数量和链接在 PR 说明与自动生成的中文验收报告中提供。

## 隐私、协议与剩余真机条件

- 扫码只支持已检查的 CatVod/FongMi Dialog 子集。依赖 `Runtime.activity()`、自建 Activity/WebView、输入表单或未知私有协议的插件未保证兼容。不绕过平台授权、权限或 DRM。
- QR 采用 SecureOn 窗口，无 URI/凭据 accessibility 文本；插件任意提示不直接显示，固定中文标签。Cookie/Token 不进入日志或报告。源切换、取消、进程断开和 bridge 关闭清理当前授权；插件自有持久化凭据的注销由合法插件执行。
- TMDB 线上使用需用户合法 Read Access Token 和可访问网络。CI 只用原创模拟响应，无账号、生产密钥、真实影视海报或私有播放链接。
- 弹幕只支持 VOD XML 明确协议，不推测第三方搜索接口；不兼容的格式被忽略。详见 [弹幕说明](M4_37_DANMAKU_ZH.md)、[扫码说明](M4_37_SPIDER_AUTH_ZH.md)、[刮削说明](M4_37_METADATA_SCRAPING_ZH.md)。
- **没有验收 TCL T7L Ultra 真机、Dolby Vision、HDR10+ 或任何指定第三方影视源。** 模拟器成功不代表这些项目成功。
- 真机还需：安装同签名构建；用真实遥控器检查方向/OK/Back、暂停续播/线路选集及 overscan；由账号持有者合法扫码；配置个人 TMDB Token 核对同名匹配；使用有权播放的 SDR/HDR 测试片，结合电视输入状态/解码器信息验证实际面板和音频输出。不得凭片名或模拟器声明真实输出。
