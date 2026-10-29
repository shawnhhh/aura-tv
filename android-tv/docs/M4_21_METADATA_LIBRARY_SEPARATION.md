# HERRIN M4.21 — 播放源与影视资料分离

## 目标

参考网易爆米花的「导入媒体后自动整理海报墙」体验，不复制其私有实现。
用户添加的 TVBox/CMS/Spider 站点提供片名、原始影片 ID、选集与播放线路；
独立的、可选的官方 TMDB 元数据服务提供展示资料。

## 数据职责

| 领域 | 权威来源 | 处理规则 |
| --- | --- | --- |
| 影片/线路/选集/URL/请求头 | 用户配置的播放源 | 绝不由元数据匹配重写；原始 ID 保持不变 |
| 海报/横幅/中文剧情简介 | TMDB 官方 API | 标题严格标准化并按年份排歧义；失败保留源内资料 |
| TMDB 评分 | TMDB `vote_average` | 标注「TMDB x.x/10」，至少 10 次投票才显示 |
| 内容大类及类型标签 | TMDB 类型与 `genre_ids` | 动漫、综艺、纪录片、电影、剧集；保留至多 5 个细分类 |
| 豆瓣评分 | **未接入** | 豆瓣法律声明要求授权；不能网页抓取或用 TMDB 分数冒充 |

## 实现

1. `ArtworkMetadataResolver` 延续官方搜索和严格标题/年份匹配，扩展读取 `overview`、
   `vote_average`、`vote_count`、`genre_ids`、年份，缓存正/负结果。
2. `FilmMetadataEnrichment` 对 `Film` 仅做显示字段的 `copy`（简介、原名、副标题、分类），不动 `id`、
   `spiderVodId`、`cmsVodId`、`playbackEpisodes`、线路 URL、历史/收藏键。
3. `MainActivity` 对不超过 80 个源内影片进行每批 3 个的后台元数据匹配，
   不阻塞原目录进入、海报加载和播放操作；用户未配置 TMDB Token 时完全离线降级。
4. 首页海报卡、横幅显示明确标注的 TMDB 评分和类型标签，详情页显示中文简介/分类/评分；
   顶部导航增加「综艺」「纪录片」，保留直播和我的。
5. 停用 TMDB 时恢复此会话中保存的播放源原图、简介、副标题、分类和评分；
   本地历史记录及选集不受影响。

## 设计及验收边界

- TMDB 官方非商业 API 要求注册、用户自行配置 Read Access Token 及署名；
  HERRIN 没有在 APK 中埋入密钥。
- 误匹配风险通过片名、年份约束降低，但不是零；未匹配时保留影视源资料。
- 本阶段没有豆瓣授权数据、导演演员详情、地区筛选或网易爆米花私有 AI 匹配服务。
- 未针对饭太硬完成真实 TCL 播放验收；**元数据增强不修复影视解析器和播放链**。
- CI 需验证 JVM 测试、Android TV 模拟器遥控器测试、静态检查和 APK 构建。

官方依据：
- [TMDB 官方 Search & Query for Details](https://developer.themoviedb.org/docs/search-and-query-for-details)
- [TMDB 官方 FAQ（署名和非商业许可）](https://developer.themoviedb.org/docs/faq)
- [豆瓣法律声明](https://www.douban.com/about/legal)
