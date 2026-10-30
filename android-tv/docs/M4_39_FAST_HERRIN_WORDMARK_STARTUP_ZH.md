# HERRIN M4.39 — 即时完整字标启动与更快首屏

## TCL 用户反馈

此前系统启动显示单独的 H，应用内从完整 HERRIN 逐个收缩为 H；进入首页前等待太久。目标：一启动就看见 **HERRIN**，以一次平滑渐隐进入可操作首页，不通过假进度延长等待。

## 已完成

1. Android 原生启动层 `launch_backdrop.xml` 从单独 H 的矢量图改为 **完整 HERRIN 字标**；新增 `launch_wordmark.xml`，OS 启动层与 Compose 使用同一字形和背景色（`#080B10`），避免首帧文字跳变。
2. Android 12+ `values-v31/styles.xml` 原 `windowSplashScreenAnimatedIcon=@drawable/launch_h` 也改为完整 HERRIN 字标适配版 `launch_wordmark_icon.xml`。系统 SplashScreen 的尺寸和裁剪属于 Android 平台规则；不会再把独立 H 作为系统启动图标。
3. Compose `Splash()` 删除原 380ms 逐字显现、150ms 等待、1550ms 折叠成 H 和 650ms 保持。现在只用矢量字标保持 **100ms** 后执行一次 **340ms 渐隐**，默认动画总预算 **440ms**；系统要求减少动画时直接过渡，不强迫用户等待。
4. 底层首页轻量 UI 可在字标渐隐时创建，首页 Hero 大图片要等字标完全退出后才启动解码，避免启动首屏因 2K 图片和模糊背景发生长帧。
5. App `MainActivity.onCreate` 至 Compose 启动层退出新增不含设备身份/私有视频 URL 的 `HerrinStartup activity_to_home_ms=...` 日志。此指标 **不包括 Android 创建进程之前的系统开销**；实际 TCL 冷启动需真机外部计时或 `am start -W` 测量。
6. Android TV instrumentation `M439LaunchWordmarkTest` 检查启动幕退出、首页真正可操作并记录实测耗时；JVM `M439LaunchMotionPolicyTest` 锁定动画预算低于 600ms，避免后续版本又添加几秒 Logo 演出。

## 同时修复来自前序 PR #45 的 CI 缺陷

- 最新 M4.38 Debug Lint 在 `RemoteTrace.event` 报 `LogTagMismatch`；改为延迟构造消息的普通函数并统一使用 `TAG`，既符合 Lint 也避免无谓的按键日志字符串生成。
- M4.38 新增大海报降采样测试期望 `RGB_565`，而真实 Android PNG 解码器可能返回 `ARGB_8888`。对不含透明度的普通卡片图片安全转为 RGB565；透明 PNG 保留 Alpha 防止图像边缘受损；全屏 Hero 保留 ARGB8888。测试构造明确的不透明源，校验真实图片。

## 交付标准

- 基于独立 `feat/herrin-m439-fast-wordmark-startup-20261030` / 版本 `0.4.39-alpha`，保持 Draft PR，无合并。
- 最新 HEAD 的 Android Debug（静态检查、JVM、Lint、assemble APK）及 Android TV Emulator 全部通过。
- Android TV 模拟器测量 Activity 至可用首页的耗时；对照 M4.38 记录，提供 CI 链接和真实失败/成功信息。
- 电视启动必须始终显示完整 **HERRIN**，没有 H 单字过渡，不在播放源还未加载好时伪装程序已启动完毕。
- 单仓的真实公网响应、TCL 系统启动画面裁剪和设备性能仍需要实机安装复测；CI 通过不能代表 Netflix 同机对照或 TCL 冷启动已达标。
