# HERRIN M4.24 — 多季详情页 / TV 遥控器 / 官方单集剧照

版本：`0.4.24-alpha`（`versionCode=31`）

## 已实现

- 当且仅当**同一 Film 内**有至少两个不同且可信的季标记时，出现横向季 Tab；未识别/仅一季仍显示 M4.23 常规选集，绝不默认补造「第1季」。支持 `S02E05`、`第2季第5集`、`Season 2 / Episode 5`。亦支持 CMS/Spider 在 `vod_play_from` / `vod_play_url` 将季名放在**线路标题**的情况，例如「第2季」「第3季」+ 集标签 `01`、`1`、`1集`、「第1集」。纯数字只有配合明确季标题才是单集号。
- 显示宽高固定 `16:9` 的官方单集剧照卡：横向滚动、聚焦弹性放大、季 Tab 方向键左右切换、下键直接进入所选季第一张卡；每张卡按确定后**直接进入原有播放链**，无二次解析按钮。
- CMS 各季走独立线路时，选集严格保留原始 `Film.id`、`PlaybackEpisode.number`、`PlaybackLine.id` 和 `uri`：例如「第2季」的 `cms-0` 不会混入「第3季」的 `cms-1` 作为备线。冲突/模糊季标签不合并。
- 观看历史继续按 `catalogScope + source Film ID + source episode index + line ID` 隔离，正确区分同编号但不同季线路。主播放 CTA 优先定位真实最近观看记录，例如 `继续播放 第3季 第5集 04:54`；没有记录则仅显示“播放 第X季 第Y集”。单集进度条仅依据真实已记录位置/时长。历史支持原始 CMS/Spider 至多 300 的索引。
- 有个人合法 TMDB Read Access Token、可信的 TMDB TV ID、官方 season/episode 编号对应时，访问 TMDB 官方 `/3/tv/{id}/season/{season}`，只采纳已验证的 `still_path`、`runtime` 和官方名称。使用 TMDB 图片 CDN 白名单、本机短期缓存、现有海报安全缓存；没有精确匹配或未授权时显示统一尺寸的中性占位，**不猜测片长和剧照**。
- 影片详情原有评分、HDR/杜比“已识别/源标称”区分、演职人员、简介、常规选集、播放器与直播功能不变。

## 已知边界及后续

1. **分成多个 Film 的季度暂不自动关联。** 即便名称相似也不能跨来源误合并，更不能把其他 Film 的播放 URL 当当前 Film 的线路。需后续从同一仓库源建立稳定的 family ID/严格标题+季度+原始 Film ID 路由后独立开发；此版本选择安全地分开呈现。
2. CMS 只有不可靠片名/合并索引、且无明确季标记时保留普通选集。标题与线路季标记矛盾或同季同集关联到多个源索引时不显示季 Tab。
3. 第三方受保护/Spider 网页播放解析是否成功是底层播放兼容性问题，不由 TMDB 数据或本季 UI 代替；播放仍交回既有 TVBox/Media3 流程。
4. 季级官方 TMDB 时长是信息标注，不能证明实际流分辨率/HDR、当前单集播放时长或片源权属。

## 验收指引

- 单元：`M424SeasonEpisodesTest`、`M424TmdbSeasonTest`，验证三个季集格式、仅单季回退、CMS 两季 line title、非法/矛盾标签、历史隔离、源 ID 不改、TMDB CDN 与授权条件。
- TV 仪表：`M424SeasonDpadInstrumentedTest`，方向右键切第3季、下键聚焦第3季第一集、确定后只调用一次播放目标。
- 回归：M4.23 原有 `M423DetailsAndQualityTest`、`HerrinUiSmokeTest`、Media3 播放、直播方向键及设置页。
- 开发机无可用 Android SDK/Gradle wrapper distribution 时只能静态检查；**以远端 Android CI 的 Kotlin 编译和模拟器仪表结果为最终验收依据**，不得将静态检查视为测试全通过。
