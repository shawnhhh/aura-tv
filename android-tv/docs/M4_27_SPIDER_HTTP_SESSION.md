# HERRIN M4.27 — CatVod HTTP 会话兼容（第一阶段）

## 背景与证据
- 既有 TCL T7L Ultra 实测先后发生 Spider 进程异常退出与能列出 80 部真实影片但最终 Media3 不出画面两种**不同**问题。
- 对照 FongMi/TV，HERRIN Spider Worker 使用 `Net(Host.NONE, site.key)`。Host.NONE 的 `getCookie()` 永远返回空串，`setCookie()` 永远失败，无法维持由 CatVod Net 请求控制的 Cookie 会话。
- 这可能影响依赖会话的**特定**站点目录、详情、播放解析；**尚无 TCL 现场网络证据证明所有失败都是 Cookie 引起**。

## 实施内容
1. 新增 `SpiderHttpSessionHost`：实现 CatVod `Host` 的 Cookie 回调，使用 OkHttp `Cookie.parse/matches` 来执行 Cookie 的域名、路径、有效期和 Secure 约束。
2. 同一 Spider 站点复用一个仅保存在 `:spider` 进程内存的会话；重新选站点/调用 Worker.close() 后清空，最多存储 128 个 Cookie，每个 `Set-Cookie` 最大 4096 字符。
3. Spider 网络对象由 `Net(Host.NONE, site.key)` 换为 `Net(httpHost, site.key)`；CatVod `Net` **只有在来源请求开启 cookie 模式时**才会利用此回调，不强制对所有请求新增 Cookie。
4. 不显示或写入 Cookie/Token 值，不跨站点共享，不持久化，不打开隐藏 WebView，不突破当前 Spider 独立进程异常隔离。
5. JVM 单测覆盖 Secure/HTTP 区分、同名 Cookie 更新、域名/路径隔离、过期删除、大小限制、源切换隔离。

## 重要限制
- 这是 CatVod 网络会话兼容增强，**不等于** FongMi 的 `JsHost`、交互式 WebView、前台 Activity、验证码/登录界面或 `proxy/localProxy` 功能已经完成。
- 部分第三方 Spider 只用自带网络客户端而非 `Spider.net`，此类插件不一定受益。
- Media3 当前仍遵循来源显式返回的请求头，**不把插件 Cookie 盲目复制到播放器或不同域名的备用媒体地址**。如确需 Cookie 播放，下一阶段应设计按实际 URL 域名/路径/重定向安全限定的逐请求凭据机制。
- 外部 DRM、非法嗅探与授权绕过不属于本项目实现范围。
- 真实 ARM/JNI、TCL 解码、杜比视界、HDR10+、自带扬声器杜比音效仍需实机检测。

## 自动化验收
- GitHub Android Debug：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
- GitHub Android TV Emulator：运行原有全量 Android instrumentation。
- 两项 CI 的实际状态以对应 GitHub Actions 为准，不得提前声称通过。
- 后续 TCL 真机使用同一有权测试的影视站点对照 TVBox 运行时 Cookie 请求、HTTP 状态码、Spider 生命周期和 Media3 首帧；仅记录脱敏指标，不记录 Cookie、视频 URL、签名或 token。
