# HERRIN M4.54 — 标准 CMS 片库点击海报直接进入播放器

## 自主排查发现的根因

HERRIN 的 `openMovie` 原来已让携带选集的片源直接进入播放、Spider 缺选集的片源也能在播放器内部补取详情，但漏掉了最常见的 **HTTP(S) CMS**：`ac=detail&pg=1` 片库列表通常只包含 `vod_id`、影片名与海报，不包含 `vod_play_url`。原分支落到 `else -> details(item)`，导致普通用户点击海报后还要经过详情页、等待选集、再点击一次播放。

## 本轮真实改动

1. 新增纯策略 `CmsOneTapPlaybackPolicy`：只有**明确选中的授权 CMS 站点**、当前影片具备原始 `cmsVodId`、不属于多季分组/Spider、且当前确实没有可播放选集，才触发播放器内的补取。已带真实选集的影片直接正常播，不重复 HTTP 查询。
2. 点击此类海报立即进入 `screen = "player"`，统一显示「正在打开影片…」的普通播放器等待画面；无技术解析进度页。
3. `LaunchedEffect` 仅对**选中影片**调用一次现有的 `tvBoxVodRepo.details(site, film, tvBoxAllowHttp)`，总超时 8 秒。用户返回或切换来源/影片时旧协程自动取消。
4. 只接受身份与选中影片相同的详情结果，并调用现有 `FilmMetadataEnrichment.mergeSourceDetails` 更新该影片；从返回的**真实线路和选集**中自动选择首个可播放项，把 episode/line 设置到原播放器，仍由 `LicensedPlaybackResolver` 与 Media3 进行授权播放，不猜测/拼接流媒体地址。
5. 没有可用选集、来源错误或超时则显示普通「暂时无法播放」以及「重新播放」「返回选集」可聚焦控件，不暴露 CMS/Spider/JAR 内部状态；重试时只重新读取该影片。
6. 限制高级工具测试 URL 不得在 CMS 真片源仍待解析时偷偷代替所选影片播放。原有专门的高级测试入口在独立场景中继续可用。

## 自动测试

- `M454CmsOneTapPlaybackPolicyTest`：5 项 JVM 场景，分别检查授权 CMS、缺站点/原始 ID、多季分组/Spider、已有线路的无重复解析、演示影片不猜媒体地址。
- **实际 Android TV 单仓大型片库集成**：扩展现有 `M438SingleWarehousePerformanceTest` 中的本地 12 站点 / 60 部影片模拟数据（列表只有 vod_id，没有选集）。完成 16 次方向键焦点与帧耗时回归后，原生遥控器按 OK 点击当前影片；本地 MockWebServer 只收到被选影片的一次详情查询，播放界面必须直接出现真实 Media3 player-root，而不能弹出二次详情页。媒体指向保留测试网段 `192.0.2.0/24`，不下载任何真实影视。
- 沿用前序 M4.43～M4.53 的遥控器连续按键、图片并发、海报刮削、播放/字幕/音轨、缓冲控制焦点及源权限安全回归。
- 最新 HEAD 必须同时通过 Android Debug / Android TV Emulator 全部 CI，才能宣布完成；原始片库数不能因测试需求而减少。

## 边界

- 多季分组、需要特殊登录/授权的源仍可能进入详情或提示来源不兼容；不会绕过来源认证、DRM 或随意执行未授权 Spider 插件。
- 首选集仅在来源提供有效且已授权的真实地址时播放；详情内容缺失则明确失败，不猜测影片 URL。
- 不能仅靠模拟器代替 TCL T7L Ultra 真机观看、HDR、4K 画质/缓冲评估。
- 基于 M4.53 Draft PR #61，创建独立 Draft；不合并 main/master。
- 版本 `0.4.54-alpha` / versionCode 59。
