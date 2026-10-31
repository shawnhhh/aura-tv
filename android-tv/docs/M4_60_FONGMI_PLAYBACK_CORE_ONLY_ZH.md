# HERRIN M4.60：仅替换播放引擎路由，不动 UI、影片资料和 TMDB

## 改动边界

本次只处理用户**已经选中影片和集数之后**的播放链路。以下文件与行为不得变动：
- `MainActivity.kt`、`AuthorizedPlaybackPanel.kt`、全部 Compose 页面/导航/遥控器焦点
- TMDB 自动刮削、匹配规则、海报、电影/演员/世界观资料；`Film` 元数据合并
- 影视源列表与站点选择、分类、搜索、详情 API；播放记录和收藏
- 用户配置、设置布局、启动动画及播放器视觉布局

涉及的生产代码仅包括 `FongMiPlaybackRouter.kt`（新增）、`TvBoxSpiderBridge.kt` 的播放入口以及 `TvBoxJsonPlayback.kt` 的解析协议。加上对应测试与本说明。

## 为什么不是直接复制 FongMi 的播放器 Activity

FongMi 的 `VodPlaybackController` 不只是播放，同时会更新 UI、历史记录、影片资料、封面、详情合并以及切站点。直接复制会违反本轮“不碰 UI 和刮削”约束。本次从 [FongMi/TV fongmi@5e191aaced194e5cac5668cc91908ecb65336222](https://github.com/FongMi/TV/tree/5e191aaced194e5cac5668cc91908ecb65336222) 中 `player/parse/ParseJob.java` 提取并适配**播放路由协议**。已有 GPL-3.0 CatVod 实现继续保留，专用 Spider 进程和现有 Media3 不替换。未来开源发行需保留相应 GPL-3.0 声明及完整对应源码。

## 具体修复

1. Spider `playerContent` 的 `playUrl=json:https://parser/?url=`：无需根配置解析器，显式使用该 JSON API。
2. `playUrl=parse:解析器名称`：按当前已应用单仓的 type=1 JSON 解析器名称选择，缺失则明确错误；不猜测其他解析服务。
3. `parse/jx=1`、HTML 观看页和 flag 匹配时，通过现有配置选路；明确视频格式或扩展名则直接保留，不把有签名的无后缀媒体误转给解析器。
4. 将 JSON 解析 URL 由此前的“整个原始 URL 强制 URL 编码”，改为与 FongMi `ParseJob.jsonParse` 相同的 `parserPrefix + originalVideoUrl`，修复某些解析 API 路径或查询参数不兼容。
5. JSON 解析响应支持 `url` / `data.url`、`header`、`ua`、`User-Agent`、`Referer`、已声明 HLS/DASH/MP4；仅相同 scheme/host/port 的解析来源与媒体允许使用解析器响应提供的 Cookie/Authorization，避免跨站泄露。
6. 保留原有 7s 连接/7s 读取超时、64KiB 响应上限、无重定向、HTTP 明确同意、媒体 URL 严格校验和独立的 Spider 进程。

## 自动回归验收

- JVM：直接视频/签名无后缀视频、MIME、显式 json/parse 指令、缺失解析器、播放线路 flag、质量对象 position、URI 授权与 Cookie 作用域。
- Android TV：沿用强制 `M459JsonParserPlaybackTest`，更新为明确的 `parse:Test JSON` 路由，并验证请求保持原始 URL、最后 Media3 实际解码超过 3 帧。
- 必须以 **最新 HEAD** 的 Debug + 全量 TV 模拟器两项 CI 全绿为准。模拟器不能代替 TCL T7L Ultra 上的第三方真实播放。

## 仍需后续开发

FongMi 的网页嗅探解析（type=0）、JSON 扩展及聚合（type=2/3/4）、QuickJS/Python/部分 ABI 原生组件、专有云盘交互与 FFmpeg/MPV 等模块需要单独评估和测试。本次没有声称复制了全部上游能力，也不提供 DRM 绕过或失效源的虚构链接。要继续接入也只能改播放内核与相关隔离适配，不能顺带改动 UI、刮削或电影资料。

**保留 Draft，不合并到 M4.59、主分支。**
