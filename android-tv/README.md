# HERRIN Android TV · M2 Alpha (stacked on approved M1.6 visuals)

真正的 Kotlin / Compose 原生 TV 应用，不使用 WebView。根目录 GitHub Pages 文件保持原样。
离线演示内容为虚构故事与 AI 生成的电影感示例素材。M2 允许用户自行提供已获授权的 HTTPS 目录、海报和测试流；应用不内置第三方影视源。

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

## M1 / M1.6 功能

- 全屏原创电影感示例画面、深色渐层、悬浮导航、横向卡片、焦点缩放与白色边框。
- 首页、电影、剧集、动漫、我的收藏、离线搜索、详情、设置；直播显示未接入状态。
- 本地收藏持久化；详情演示选集/线路；播放明确显示无真实视频流。
- HERRIN 从右逐字淡出并收缩至 H，约 2.8 秒；OK / Back 跳过。
- 设置中的减少动态效果，以及系统动画时长为零时跳过启动动画并禁用焦点缩放动画。
- D-pad 原生焦点与滚动；返回详情来源、恢复卡片焦点和列表位置。首页 Back 交由系统退出。

## 代码边界

`CatalogSource` / `DemoCatalog`：目录与搜索；后续网络适配器在后台加载数据，UI 应改为异步状态。
`FavoritesStore` / `Preferences`：SharedPreferences 收藏与动态偏好。
`PlaybackSource`：输入影片 ID、集数、线路；返回媒体 URI/headers 或明确不可用原因。
M2 Alpha 新增可配置 HTTPS 目录 JSON、私有缓存和 Media3 播放组件；无用户配置时仍采用 `DemoPlaybackSource` 离线占位。不会自动解析或提供任何版权影视源。
导航状态保存在 Compose saveable state，列表滚动状态独立于详情页面。
后续真实播放器应由独立生命周期组件承载，不能直接把解析工作放在 UI 线程。

验证记录与 TCL 遥控器验收见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。
FongMi 结构、许可及集成方案见 [docs/FONGMI.md](docs/FONGMI.md)。


## M2 Alpha 新增内容

用户可在 **设置** 中填写获授权的 HTTPS 目录 JSON 和独立的测试媒体地址。云端编译与离线自动测试可验证目录解析、设置 UI 和播放器集成，但**没有已授权测试流时不能宣称实播通过**。详见 [M2 目录与播放适配文档](docs/M2_PROVIDER_PLAYER.md)。


## M2.2 — 每集授权线路与续播
- 在用户提供的授权目录 JSON 中配置 `playback.episodes[].lines[]`，即可为不同影片集数指定各自的 HTTPS 播放地址；不会从影视站抓取或猜测线路。
- 原有 `episodes` 数字目录继续兼容，未配置播放线路仍显示演示占位。
- 续播记录保存在本机并按目录地址、影片、集数和线路隔离；不会保存媒体 URL 或授权凭据。
- Android TV 模拟器自动测试通过临时的、只供测试使用的 HTTPS 服务，验证 FFmpeg 原创 MP4 与 HLS 的真正网络解码出帧。具体 JSON 示例与适用范围见 [M2.2 文档](docs/M2_2_EPISODES_RESUME.md)。


## M2.3 播放器（字幕、音轨、进度）

通过遥控器操作播放器底部真正可聚焦的播放、±10 秒跳转、音轨和字幕控制。音轨与字幕从当前 Media3 实际解码轨道读取，不会凭空显示不存在的语言。

授权片源可通过每条 `playback.episodes[].lines[]` 的 `subtitles` 可选字段提供 HTTPS WebVTT 字幕。详见 [M2.3 文档](docs/M2_3_PLAYER_CONTROLS.md)。在线播放不内置未经授权的流；TCL 实机测试仍待完成。

## M2.4 多目录源管理

「设置」支持最多 10 个用户授权的 HTTPS JSON 目录保存与切换、返回离线演示、旧单目录地址自动迁移、按目录隔离收藏。通过原来的 `catalog_url` 接口继续驱动远端目录加载器，不复制 FongMi/TV GPL 代码或集成第三方抓取器。详见 [M2.4 文档](docs/M2_4_CATALOG_PROFILES.md)。

## M2.5 顶部导航焦点过渡

针对电视遥控器切换 Tab 僵硬的问题：导航下方增加跟随实际按钮位置连续滑动的细光轨，快速左右反向时动画可被打断并重新指向；焦点按钮仍立即变白，离开导航后光轨淡出。开启「减少动态效果」或系统关闭动画后直接切换。详见 [M2.5 文档](docs/M2_5_NAV_MOTION.md)。120Hz 实机帧率仍待验证。
