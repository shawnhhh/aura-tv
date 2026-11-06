# Herrin Apple · M0

独立的 **iPhone + iPad + macOS** 原生 SwiftUI 工程骨架。**不支持 tvOS**，不修改 Android TV / FongMi 播放核心。

## M0 已规划边界

- 一套 SwiftUI 源码构建两个 App Target：iOS (iPhone + iPad)、macOS 原生。
- 通过 TMDB Read Access Token 加载推荐、电影、剧集与搜索；Token 仅保存在本机 Keychain，**不内置私人 API Key**。
- 国内网络首选 `https://api.tmdb.org/3`，备用官方入口 `https://api.themoviedb.org/3`。仅网络不可达或 5xx 故障回退；401/403/429 不切换域名。
- 按设备自动选择图片：**iPhone 竖版 `poster_path`（2:3）**；**iPad / Mac 横版 `backdrop_path`（16:9）**。数据层同时保留两个字段；缺图时另一方向图片等比留边显示，绝不硬拉变形。
- 本阶段只是基础目录与 TMDB 预览：**还未实现真实影视播放、NAS、直播、TVBox Spider、收藏同步或完整 UI**。

## 本地构建

需要 macOS、Xcode、XcodeGen。**无法在 Linux 上验证 Xcode 构建。**

```sh
cd apple
xcodegen generate
xcodebuild -project HerrinApple.xcodeproj -scheme HerrinAppleIOS \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
xcodebuild -project HerrinApple.xcodeproj -scheme HerrinAppleMac \
  -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO build
```

首次启动后在「设置」里输入你自己的 **TMDB API Read Access Token**，不可将 Token 放在源码、配置文件、GitHub CI 或截图内。

## 后续阶段（不属于 M0 已完成内容）

1. iPhone/iPad 原生详情和 AVPlayer：MP4/HLS、字幕、音轨、画中画。
2. macOS 桌面窗口、快捷键、拖入本地视频、全屏播放。
3. NAS SMB/WebDAV 与授权网络媒体、M3U/EPG 直播、标准 CMS JSON。
4. 收藏与续播、缓存、安全审查与真机性能测试。
5. 研究复杂 TVBox Spider/脚本源在 Apple 平台的技术和发行边界；**不保证与 Android/FongMi 100% 兼容**。

## 工程原则

- 所有业务与平台专属实现分开；以后可抽离 Android 与 Apple 共用的数据协议，而不是现在强行跨平台运行 Android 的 Java 运行时。
- **不调用或依赖 Superpowers**；使用原生工程工具和普通自动化测试。
- 仅在独立开发分支与 Draft PR 推进，不合并 `master`，不动正在开发的 Android TV P0。
