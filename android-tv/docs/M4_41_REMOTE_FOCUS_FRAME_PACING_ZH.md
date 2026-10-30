# HERRIN M4.41 — D-pad 跟手与背景帧负载优化

## 设计与对标边界
用户期望 TCL T7L Ultra 的遥控器连续切影片、上下切海报行接近 Infuse / VidHub 的跟手观感。两款闭源产品的内部代码没有可核实依据，不声称复制其内部机制。参考 Android Compose Foundation TV 焦点/列表最佳实践及 Jellyfin Android TV、tv-focus-navigation-kit 的公开遥控设计方向：**焦点反馈先于耗时工作、列表位置稳定、图片延迟且可取消、详情/播放专属数据不得挤占浏览帧**。

参考资料：
- https://developer.android.com/training/tv/playback/compose/lists
- https://developer.android.com/develop/ui/compose/performance/bestpractices
- https://github.com/iFan6oy/tv-focus-navigation-kit
- https://github.com/jellyfin/jellyfin-androidtv

## 源码里实际识别出的高开销路径
1. 旧代码：每次海报获得焦点写入 `filmId`，全屏 `Crossfade(targetState = displayFilm)` 马上重建跨帧高分辨率海报/背景；快速连按可不断切换 360ms 图片动画，照片背景还涉及模糊 GPU 绘制。
2. 同一 `filmId` 变化使浏览主组合继续执行剧集索引、组季布局、最近播放进度读取；这些只应在进入详情/播放时需要。
3. TMDB 季剧集详情的 `LaunchedEffect` 原先 keyed by film ID 而不是 screen，即使只在海报墙浏览也可能被开启。
4. 全屏播放器已覆盖首页时，旧 `Box` 仍在底层绘制英雄背景、暗色遮罩和图片切换动画，与 Media3 视频争抢渲染时间。

## M4.41 变更
- **即时焦点 + 延迟背景**：当前 `filmId`、焦点描边、Hero 标题、确定键与播放动作不延迟。仅背景资源在焦点稳定 `200ms` 后更新；连续 D-pad 会取消过期的 `LaunchedEffect`，只保留最后停留的目标。
- 全屏背景 `Crossfade` 从 360ms 缩至 170ms，而且仅在停留稳定后触发；单个正在下载的图片仍遵守先前的缓存、压缩和网络授权策略。
- 播放和直播全屏期间，**不再执行首页大背景的合成与解码工作**。
- 浏览、搜索、设置和站点页不再每次切焦点计算 `SeasonEpisodes` / `GroupSeasonIndex` 或逐集读取本地观看历史。详情/播放仍保留原有最新续播进度和季集分组。
- TMDB 季剧集详情只在进入详情页时加载。此前的 240 条后台资料匹配、用户主动重新刮削和延迟匹配策略不受影响。
- 使用 Android TV API 30 仿真基线 `M438SingleWarehousePerformanceTest` （12 站点、60 电影、16 次 DPAD）继续检测焦点耗时；额外 3 项 M441 JVM 策略用例防止以后重新把视频背景与选片热路径耦合。
- `0.4.41-alpha`（versionCode 46），单独 Draft PR；所有旧 PR 与 master 保持不变。

## 自动化验收边界
本 PR HEAD 的 **Android Debug + JVM + Lint + APK** 和 **Android TV Emulator 全量 instrumentation** 都通过才宣布自动化回归合格，包含 Media3 真出帧、焦点移动、播放历史、TMDB、Spider、IPTV 功能。性能基线保留原始真实按键 P95 门槛，不删除测试或降低断言。

模拟器的 D-pad `sendKeyDownUpSync` 包括工具注入和 `waitForIdle` 的耗时，不是物理遥控器发出命令到电视像素变化的精确硬件延迟。任何“超过 Infuse / VidHub”、“已经 60fps 丝滑”结论均需要相同设备、同媒体库、同网络条件下用 TCL 真机录像/帧时数据验证；不能将策略参数当作已经实现的体验指标。
