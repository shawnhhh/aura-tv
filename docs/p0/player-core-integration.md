# FongMi HTTP 播放核心生产集成

上游固定 FongMi/TV `305dff233cde512e2c83f71529b7c3ecfd61fefc`，GPL-3.0，原作者 FongMi。
新移植文件均保留来源、日期和宿主差异，vendor 原版 43 文件不变。

## 真实生产链

已批准配置 → bridge/worker → 按 JAR 与批准会话缓存的 DexClassLoader → Spider
search/detail/playerContent → NativeFongMiProxy/Route → 原 ParseJob → PlaybackMedia
→ HerrinPlaybackAdapter → PlayerManager → MediaItemFactory → ExoMediaSourceFactory/Media3
→ 原 Herrin surface、MediaSession、错误/进度事件与遥控器。

Film 凭据绑定完整运行时指纹、站点、影片 ID/VOD ID；每条线路再绑定集数、ID、flag 和
opaque URI。凭据在 Binder 保留，详情/播放在 client 和 worker 检查；TMDB 仅改显示资料。
授权 secret 只在内存/Binder，显式 close 撤销；同批准来源 transport 重建可验证旧 Film。
同 JAR 切站保留 ClassLoader/Init，实例与 HTTP Cookie 仍逐站释放。Init 缓存的旧静态 URL
仅通过同 JAR/grant 曾实际签发的 port+token 重绑定；没有放宽 HTTP token/site 校验或 do。

## 实际源码适配边界

- PlayerManager 移植固定上游 start/startMediaItem、播放尝试身份、播放器事件、超时、控制
  与 release 的 HTTP/Exo 路径；注入真实 Exo/Handler，ParseJob 继续在隔离 worker 执行。
- MediaItemFactory/PlaySpec 接实际 PlaybackMedia：媒体 ID、URI、请求头、MIME、字幕和
  metadata；字幕 ID/语言/default 分支与上游一致，未改成虚假媒体或空壳 API。
- Compose 不再自行 setMediaItem/prepare/release；重试和生命周期都经同一 manager。
  Native FongMiExoMediaSession 只保留兼容门面，内部也经同一生产 adapter，没有第二套数据栈。
- 发布版 Media3 1.9.4 不含固定上游 IsoUri/DiscMenuController/setAdblock/LocalClearKeyLicense
  等定制 ABI；未编译整份原 PlayerManager 的 MPV/光盘/特效分支，没有创建 stub 冒充提供。
  因此本批是实际 HTTP 播放核心接管，不宣称完整定制 fork 的所有功能已移植或 P0 完成。

最终以本提交串行 CI 的 MP4/HLS/TS 出帧、音轨和遥控器全量结果为准；TCL 未实测。
