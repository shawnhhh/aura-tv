# HERRIN 0.4.34-alpha：TCL 真机 Spider 播放地址获取异常分诊与有限恢复

## 用户真机事实（2026-10-29）

TCL T7L Ultra 上安装 0.4.33-alpha，导入用户授权的 TVBox 影视仓配置 `http://www.饭太硬.net/tv`，自动读取 Spider 影视点并显示影片、海报、详情。用户两次选片时画面先出现“正在通过 Spider 解析播放线路”，随后出现：“Spider 子进程异常退出，最后阶段：获取播放地址；请重试当前操作”。主进程和影片 UI 未崩溃。

**可确定：** 与 MP4/HLS/DASH 解码、视频出帧、杜比/HDR 不同，此次问题发生在 **Spider.playerContent** 或其后续进程存活期；Media3 尚未拿到可播地址。**不能确定：** 原生 SIGSEGV、脚本运行时兼容、宿主强制超时、系统内存终止、插件有意主动退出，需要 Android OS 的退出记录或真机 logcat 证据区分。M4.33 之前模拟器屏幕测试只验证合成测试媒体与 CatVod API，并未运行该真实站点 JAR。

## 与 FongMi TVBox 参考行为对比

| 环节 | TVBox/FongMi | M4.33 HERRIN | M4.34 |
| --- | --- | --- | --- |
| 站点/Spider init/home/detail | FongMi CatVod 插件入口 | 独立进程执行，可加载目录 | 保留 |
| playerContent(flag,id,vipFlags) | Spider 插件解析真实播放 URL | 获取地址阶段可能子进程异常退出 | 分离崩溃类别并给出阶段诊断 |
| Spider proxy() HTTP | TVBox 本机路由 | M4.32 已实现仅 127.0.0.1、本地会话令牌 | 保留 |
| 进程硬超时 | 按站点设置、宿主超时处理 | 服务 32 秒强制 kill，与真实 JNI 崩溃误报 | 40 秒看门狗独立标记，客户端 44 秒边界 |
| 原生进程退出原因 | 可从 Android 系统诊断 | 只显示最后阶段 PLAYER | Android 11+ `ApplicationExitInfo` 仅取自己的 :spider 进程退出原因 |
| 已有直链 | 播放 Result 交给播放器 | Spider 崩溃则整条播放失败 | 仅在崩溃/超时、已有合法直接媒体地址时降级交给 Media3 |

## M4.34 的代码改动

1. `SpiderExitDiagnostics.kt`：仅查询本应用最近 90 秒内 `:spider` 进程的系统退出原因，显示固定中文分类（原生组件崩溃、Java 未捕获异常、ANR、系统内存不足、主动退出、系统信号）。不收集或展示 tombstone、堆栈、异常消息、URL、Cookie、扩展配置等敏感数据。Android 10 及以下自动不启用。
2. `SpiderStartupTrace.Stage.WATCHDOG`：宿主 watchdog 即将强制 kill 前落盘固定阶段文本，**不再把宿主超时当成已证实的原生崩溃**。看门狗调整至 40 秒，IPC 最大等待 44 秒，保留现有可中断操作自身 30 秒预算。
3. `SpiderDirectPlayback.kt`：仅当主进程已认证当前应用的站点、影片 ID 含当前 site key 派生前缀且选中集数返回了真正的 HTTP(S) `.m3u8` / `.mpd` / `.mp4` 直链，且 Spider 在播放阶段死亡/超时时，允许使用其原始 URL 以及仅 `User-Agent`/`Referer` 两类基础请求头交给 Media3；绝不把原站点 Cookie/Authorization 发送到可能不同域名的媒体 CDN。对 HTTP 用户许可、非媒体网页、其它插件 opaque ID、其它站点头严格拒绝；不寻找、更改或猜测媒体地址，也不支持 DRM 绕过。
4. `M434SpiderPlaybackRecoveryTest.kt`：覆盖站点隔离、不同媒体域名的 Cookie/Authorization 防泄露、允许的媒体扩展名、签名 query 透明保留、HTTP 许可、错误类型与隐私保护，以及 WATCHDOG 与 PLAYER 分离。

## 本轮验收要求

- Android Debug（Gradle JVM 测试、Lint、APK）与 Android TV 模拟器全量 instrumentation 两条 CI 必须在本分支最新 SHA 成功；
- 项目其他站点、收藏、遥控器焦点、直播、原有原创 MP4/HLS/DASH 屏幕截图测试保持不变；
- **M4.34 CI 即使全绿，也不表示真实饭太硬源已在 TCL 上恢复**，因为它的 Java JAR/JNI、网络条件和解密/鉴权环境没有在模拟器运行；
- 真机下次出现故障时应区分显示「最后阶段：获取播放地址」加「系统原因：原生组件崩溃」或「WATCHDOG 超时」等。拿到具体类别后，才能针对 JNI/ABI/运行时、内存或超时继续实施有效修复；
- 如选中的线路已提供明确的合法直链，可直接尝试恢复播放；不适用的 opaque ID 保留原错误，不假装播放成功。

保持 Draft，不修改其他 PR 或主分支。
