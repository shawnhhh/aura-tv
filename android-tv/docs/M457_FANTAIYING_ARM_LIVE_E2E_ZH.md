# HERRIN M4.57 饭太硬 ARM 真机完整播放验收

## 目标与边界

本测试不会把“配置 JSON 可下载”或“JAR 的 MD5 正确”冒充实际视频可播放。只有在 **ARM Android TV** 上确实执行指定 `csp_*` Spider、打开一部影片的真实详情和指定线路，并由 **Media3 输出至少一帧视频**，该测试才通过。

普通 CI 的 311 项 JVM 与 113 项 x86 模拟器测试无需真实饭太硬源，也不会执行其远程 JAR；本测试使用单独的 Gradle `-PherrinLiveSourceTest=true` 开关和专用 AndroidTest APK，不会自动开启。

默认站点 key 为 `YGP`（饭太硬配置中显示为“荐影┃预告片”），用户可选择自身有播放权限的其他站点。该站点及插件由第三方提供，可能随时不可用、要求登录、使用其他播放器或网页解析。

## 自动构建和验收命令

1. 在 GitHub Actions 的 **HERRIN ARM FanTaiYing E2E APK (Compile Only)** 下载附件，解压取得两个 APK：`app-debug.apk` 与 `app-debug-androidTest.apk`，以及脚本 `run_fantaiying_arm_e2e.sh`。
2. 在自己的 Mac 或 Windows 电脑上通过官方 Android SDK `adb` 连接 TCL Android TV（电视开发者选项允许调试）。需先确保 `adb devices` 只显示目标电视。无线 ADB 连接由电视授权，不能由云端绕过或自动获取。
3. 在脚本所在目录执行：

   ```bash
   bash run_fantaiying_arm_e2e.sh app-debug.apk app-debug-androidTest.apk YGP
   ```

脚本校验设备为 ARM ABI，安装两个同版本的 APK，自动执行唯一的真机测试，输出 `herrin-fantaiying-arm-e2e-日期.txt`。不要求手动点击海报或逐集操作。

测试分为：`config` → `spider_browse` → `spider_details` → `spider_player` → `media3_first_video_frame`。只在实际帧渲染成功后输出 `PASS_real_decoded_video_frame`。超时、插件授权要求、无目录、无选集、无合法直链或 Media3 错误均会失败并保留具体阶段。日志不会包含原始影视 URL、Token、Cookie 或访问凭据。

## 安全与已知局限

- 运行此测试表示用户明确选择第三方 `csp_*` 站点、允许应用在其 TV 的隔离 `:spider` 进程加载真实插件。第三方 JAR 属于可执行代码；校验 MD5 只能保证文件与来源配置一致，**不能证明来源可靠**。不要对未经信任的来源开启自动执行。
- 本测试在真机上发起真实网络请求，可能消耗流量或触发插件登录/验证码；不会自动绕过这些限制。
- 因无远程访问用户 TCL 的权限，GitHub Runner 不会冒充 TCL 真机执行测试。默认 x86 模拟器也不代表 ARM 库可运行。
- 失败时提供脱敏 `txt` 日志及测试时所选站点 key，可精准定位 Spider 初始化、解析、HTTP 失败还是解码问题。切勿分享私人 Token、完整 Cookie 或敏感媒体地址。

## 2026-10-31 公网静态实测

- `http://www.饭太硬.net/tv`：HTTP 200，JPEG 尾随 JSON 包装解析成功，共 48 站点，45 个 `csp_*`。
- JAR：1,386,820 字节，HTTPS 200，MD5 匹配配置、包含 `classes.dex`。
- ARM64 `assets/ftyguard_v8.so`：102,440 字节；ARM32 `assets/ftyguard_v7.so`：81,520 字节。均满足 Herrin 的单库大小要求。
- 直接 CMS 站点在公网测试环境未返回可靠播放直链，不能把 JAR 有效等同于影片播放成功。
- 基线版本 `73349f8f`：311/311 JVM、113/113 x86 模拟器成功；**没有**完成 TCL 实机第三方视频出帧验收。

真实来源诊断 GitHub Actions 工作流：`.github/workflows/herrin-fantaiying-live-probe.yml`。真机测试构建：`.github/workflows/herrin-fantaiying-arm-e2e-build.yml`。
