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

## M4.2 TVBox 直播与标准影视 JSON 接口（Draft 开发分支）

在 M4.1 多仓/单仓配置之上：可直接解析单仓 `lives[].channels`，或手动导入 M3U、TVBox TXT 与内嵌直播 JSON；将已授权频道保存在应用私有存储中供下次启动使用。配置 JSON 经校验后按 URL 缓存，远端变成图片/报错时只回退到**同 URL** 已验证的旧 JSON；用户也可手动粘贴从旧 TVBox 备份的 JSON 并绑定到来源地址。可以对 `sites[].api` 为直接 HTTP(S) 地址的**标准 CMS JSON** 尝试读取影片列表与自带 HTTPS 播放线路。

不包括任意 TVBox Spider/JAR/JS 插件、加密配置或私有采集协议的执行；不保证任意 TVBox 源均可播放。

### M4.3 · 图片尾随 Base64 的 TVBox 单仓解封装

进一步分析证实：`http://www.饭太硬.net/tv` 等地址虽然响应 MIME 标为图片，**但 JPEG 结束标记之后含有 `标记**Base64(JSON)` 配置尾部**。M4.3 将此尾部安全解码，兼容 `//` 注释，校验 `sites/lives` 并保存为本机 JSON 缓存。该真实源在本轮外网取样中可恢复 **49 个影视站点、6 组直播**，但影视站点几乎均为 `csp_` Spider 适配器，完整影片搜索、解析与播放仍待实现。详见 [M4.3 图片配置诊断报告](docs/M4_3_TVBOX_IMAGE_WRAPPER.md)。

## M4.1 TVBox 多仓安全预览（基础能力）

**设置 → TVBox 多仓 / 单仓配置**，可粘贴 TVBox `urls` 多仓入口并用遥控器选择单仓；也可直接粘贴包含 `sites`、`lives` 的单仓 JSON。显示站点名称、直播组名称及需要独立适配器的提示。检测到明确的 HTTPS M3U 链接时，可手动导入现有直播模块。HTTP 链接需要本次手动授权，应用不会自动打开上次使用的仓库。详见 [M4.1 兼容范围与测试](docs/M4_1_TVBOX_MULTIWAREHOUSE.md)。

**兼容范围有限**：M4.1 仅解析 TVBox JSON 元数据；M4.2 增加直播数据与标准 CMS JSON 直连，仍不运行第三方 Spider/JAR/JS。

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

## M2.6 目录编辑与备份

已保存目录可改名、更换 HTTPS 地址、双确认删除；目录列表可以用户主动复制 JSON 备份或从 JSON 合并导入（校验失败整个导入回滚，现有目录不覆盖）。详见 [M2.6 文档](docs/M2_6_SOURCE_EDIT_BACKUP.md) 和 [完整功能路线图](docs/PRODUCT_ROADMAP.md)。

## M2.7 播放异常恢复

授权媒体加载时显示缓冲状态；失败后提示可读原因并提供遥控器可聚焦的「重试播放」，请求超时有界，更新同一地址的请求头会重建播放器。详见 [M2.7 文档](docs/M2_7_PLAYBACK_RELIABILITY.md)。

## M2.8 合法授权直播

设置中可填写自己的 HTTPS IPTV M3U 列表，直播 Tab 展示频道与分组，遥控器选择后由 Media3 播放；解析器拒绝 HTTP 链接与 HLS 分片清单误导入，不附带频道源。详见 [M2.8 文档](docs/M2_8_AUTHORIZED_LIVE.md)。

## M2.9 搜索与筛选

本地搜索增加电影／剧集／动漫类别筛选、结果计数和最新年份优先排序，全程遥控器可操作，不向外部发送搜索词。详见 [M2.9 文档](docs/M2_9_DISCOVERY_FILTERS.md)。

## M3.0 设备诊断与缓存维护

设置中可查看系统实际输出刷新率和 HERRIN 高刷请求值（不冒充渲染帧率）、复制隐私友好的诊断摘要，以及二次确认清理海报缓存。详见 [M3.0 文档](docs/M3_0_SETTINGS_DIAGNOSTICS.md)。

## M3.1 稳定签名与安装更新

提供独立手动签名工作流（仅审核后 master 可运行），用 GitHub Secrets 保护发行密钥并校验 APK 签名；调试版保持无密钥构建。第一次稳定签名需用户自己生成并安全保管密钥，暂不能宣称可无损覆盖旧的临时 debug 签名。详见 [M3.1 文档](docs/M3_1_SIGNED_RELEASE.md)。

## M3.2 直播频道收藏

已配置授权 M3U 时，可通过遥控器单独收藏频道，并切换「只看收藏」筛选；仅在本机存储哈希标识，按播放列表地址隔离，不保存明文频道链接。详见 [M3.2 文档](docs/M3_2_LIVE_FAVORITES.md)。

## M3.3 可选 XMLTV 节目单

可另填自己的 HTTPS XMLTV 节目单，按 IPTV 的 tvg-id 匹配频道并展示「正在播出」；支持时区，使用有界且禁用外部实体的 XML 解析器，节目单故障不影响直播播放。详见 [M3.3 文档](docs/M3_3_XMLTV_EPG.md)。

## M3.4 节目单预览与 Android TV 兼容

直播频道获得遥控器焦点后，在频道栏下方预览「当前 / 下一档」节目及当前节目进度；只使用已读取的用户授权 XMLTV 数据，不因焦点切换而发起网络请求。修复 API 30 Android XML 解析器缺少 JAXP 安全特性开关造成的 M3.3 模拟器测试失败，继续拒绝 DTD/实体注入。详见 [M3.4 文档](docs/M3_4_LIVE_GUIDE.md)。

## M3.5 直播分组筛选与重试

当用户提供的 M3U 包含不同分组时，直播页提供原生遥控器分组切换，与已有「只看收藏」可叠加筛选；显示当前频道数并保留源列表顺序。支持手动刷新频道和节目单，刷新失败时保留同一地址上次加载的频道或节目；更换直播地址会先清空旧数据以防跨源显示。详见 [M3.5 文档](docs/M3_5_LIVE_GROUPS.md)。

## M3.6 支持压缩 XMLTV 节目单

自己的授权 HTTPS XMLTV 地址现在也可以指向 `.xml.gz` 文件。应用根据实际 gzip 文件头识别并有界解压，传输数据最大 4 MB，解压后 XML 最大 6 MB，同时限制节目条数；损坏、超限或恶意实体不会影响直播频道播放。详见 [M3.6 文档](docs/M3_6_GZIP_XMLTV.md)。

## M3.7 直播频道快速查找

直播页新增「查找频道」，通过原生电视输入框按名称或 `tvg-id` 在**本机**查找频道，能够与分组和收藏条件叠加，不会将关键词发送到网络。支持清空搜索、无结果提示和切换直播源后的状态重置。详见 [M3.7 文档](docs/M3_7_LIVE_SEARCH.md)。

## M3.8 直播输入法返回频道焦点

电视系统键盘输入完成或在搜索输入框按返回键时，主动关闭键盘并将焦点移到第一个匹配频道（无结果则移到「清空关键词」），同时把横向频道列表滚动到开头。新增真实 Android TV 模拟器 Done → D-pad → 收藏操作的回归用例。详见 [M3.8 文档](docs/M3_8_LIVE_IME_FOCUS.md)。

## M3.9 授权实播验收与状态面板

播放器新增可通过遥控器开启的「查看实播状态」，显示 Media3 实际播放状态、视频解码出帧、分辨率、丢帧和当前音轨/字幕轨数量，不包含媒体地址或访问凭据。完整自动化验收基于本仓库自制 HTTPS MP4/HLS/字幕测试素材，新增用户授权 M3U + XMLTV 对应关系、切换频道解码释放、模拟网络失败后的遥控器重试。实际 TCL T7L Ultra 和用户授权片源的实播仍需实机执行。详见 [M3.9 文档](docs/M3_9_LIVE_PLAYBACK_ACCEPTANCE.md)。
