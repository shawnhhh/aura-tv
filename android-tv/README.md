# HERRIN Android TV · M1

真正的 Kotlin / Compose 原生 TV 应用，不使用 WebView。根目录 GitHub Pages 文件保持原样。
所有片名、故事与内置矢量插画为本项目原创虚构示例，不含第三方电影海报、网络图片或未授权流。

## 构建与安装

Android Studio 打开本目录。使用 JDK 17、Android SDK 35 / Build Tools 35.0.0。
Gradle 8.9、AGP 8.7.3、Kotlin 2.0.21。首次同步需要访问 Google Maven / Maven Central / Gradle。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n tv.herrin.app/.MainActivity
```

GitHub Actions `HERRIN Android Debug` 在 PR 和开发分支 push 时构建。使用 Ubuntu Runner 的 SDK、Temurin JDK 17，setup-gradle 安装 8.9。
构建成功后，在该次运行页面 Artifacts 下载 `HERRIN-debug-apk`，解压得到 `app-debug.apk`。
Artifact 保留 14 天；需登录 GitHub。仅 debug 签名，不需要生产签名或密钥。

## M1 功能

- 全屏原创风景插画、深色渐层、悬浮导航、横向卡片、焦点缩放与白色边框。
- 首页、电影、剧集、动漫、我的收藏、离线搜索、详情、设置；直播显示未接入状态。
- 本地收藏持久化；详情演示选集/线路；播放明确显示无真实视频流。
- HERRIN 从右逐字淡出并收缩至 H，约 2.8 秒；OK / Back 跳过。
- 设置中的减少动态效果，以及系统动画时长为零时跳过启动动画并禁用焦点缩放动画。
- D-pad 原生焦点与滚动；返回详情来源、恢复卡片焦点和列表位置。首页 Back 交由系统退出。

## 代码边界

`CatalogSource` / `DemoCatalog`：目录与搜索；后续网络适配器在后台加载数据，UI 应改为异步状态。
`FavoritesStore` / `Preferences`：SharedPreferences 收藏与动态偏好。
`PlaybackSource`：输入影片 ID、集数、线路；返回媒体 URI/headers 或明确不可用原因。
M1 使用 `DemoPlaybackSource`，没有视频解码、真实播放、账号或在线接口。
导航状态保存在 Compose saveable state，列表滚动状态独立于详情页面。
后续真实播放器应由独立生命周期组件承载，不能直接把解析工作放在 UI 线程。

验证记录与 TCL 遥控器验收见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。
FongMi 结构、许可及集成方案见 [docs/FONGMI.md](docs/FONGMI.md)。
