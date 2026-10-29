# HERRIN M4.33 — Android TV 实际屏幕图像验收

## 为什么做这个阶段

先前 M4.32 Draft PR #39 已通过 Debug 和 API 30 x86 Android TV 模拟器的 89/89 项回归。特别是 CatVod `getProxyUrl()` → `Spider.proxy()` → 本地 HTTP → Media3 的原创 MP4/HLS **真实解码出帧**测试成功。

但 Media3 `renderedOutputBufferCount > 3` 只说明解码输出的帧计数增加，不能单独断言 Android TV 图像合成器显示的中间视频区域不是黑屏。用户希望“像电视一样看画面是否正常”。

## 本阶段实际新增

- 新增 `TvVisibleFrameEvidence`（仅 androidTest）：使用 Android `UiAutomation.takeScreenshot()` 从正在播放的 Android TV 模拟器**实际屏幕**截取当前显示画面。
- 对屏幕中部（避开顶部 HERRIN 按钮与状态文字）以 16×12 网格抽样亮度、颜色变化、不同颜色数量，要求原创彩色视频存在肉眼可辨的非黑色画面。允许最多 8 次截图、每次相隔 350ms，避免 SurfaceView 合成异步造成误判。
- 接入 M4.32 已验收的两条真实播放测试：
  - Java Spider 本地代理 HLS → 原创 TS 分段 → Media3 出帧 → **屏幕有色画面**；
  - Java Spider 本地代理 MP4 → Media3 出帧 → **屏幕有色画面**。
- 每个测试成功或失败都会通过 `UiAutomation.executeShellCommand(screencap)` 将实际 PNG 写到 `/data/local/tmp/herrin-test-evidence/`，由既有 `android-tv/scripts/emulator_ci.sh` 收集并上传到 `HERRIN-emulator-evidence` CI artifact。可人工点开核查，没有第三方电影内容和播放凭据。
- 仅使用自制测试素材，不请求任何第三方影视源、账号、Cookie 或 DRM。
- 版本更新 `0.4.33-alpha` / versionCode 38。

## 预期证据级别

- `M4.32`: 真实网络媒体请求、Media3 轨道解码器计数、真实输出帧；
- **`M4.33`**: 进一步确认模拟器屏幕合成后的像素可见，并保留测试过程中截图。这比单独读取 Media3 计数更接近“电视能正常显示视频”。
- **依然不能**替代 TCL T7L Ultra 真机：Android TV x86 模拟器使用 SwiftShader 图形栈，与 TCL ARM SoC、系统定制 DRM、厂商 Dolby Vision/HDR10+ 和电视扬声器输出不同。

## 验收边界

只有本 M4.33 PR 实际通过 Debug 和 TV 模拟器 CI，且 `SpiderLocalProxyPlaybackTest` 两条屏幕可见性测试均为 PASS，才能声称“模拟器已验证实际屏幕可见播放”；若 CI 因画面黑屏失败，保留 PNG 并进一步按截图和日志定位，不可以只移除断言让它变绿。

所有 PR 保留 Draft，不合并主分支。下一最终阶段在用户 TCL 上安装测试版，选同一有权使用的影视源，观察真实画面、声音、网络错误代码，以及 Dolby/HDR 的实际输出。
