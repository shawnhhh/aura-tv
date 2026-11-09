# 阶段一：播放核心审计与验收边界（2026-11-09）

基线 d7f7788；原固定上游 FongMi/TV 305dff233cde512e2c83f71529b7c3ecfd61fefc，43 文件完整性门禁保留。
本记录取代旧 compatibility-matrix 和 PINNED_UPSTREAM 中“PlayerManager 仍未接入”的历史状态描述。

## 真实生产链

唯一解码器：AuthorizedPlaybackPanel → HerrinPlaybackAdapter → com.fongmi.android.tv.player.PlayerManager →
FongMiExoPlayerFactory / ExoMediaSourceFactory / MediaItemFactory → 发布版 Media3 1.9.4。
PlayerManager 实际拥有初始化、启动、缓冲期限、轨道选择、重试时媒体身份、停止和释放。
Compose 负责遥控器、进度、错误展示和恢复策略；没有第二个播放器内核。

Java Spider：TvBoxSpiderRemote（独立 Binder 进程、来源身份/授权/期限/退役）→ TvBoxSpiderWorker（受批准 Dex/JAR）→
playerContent → NativeFongMiRoute → FongMi 原生 Proxy 适配 → FongMiParsePlayback → 移植 ParseJob。
HTTP Spider、CMS 解析最终复用相同 ParseJob 与唯一 PlayerManager。
生产源集不存在旧 SpiderLoopbackProxy / SpiderProxyPlayback / HerrinSpiderHlsProxyRewrite；静态门禁仍阻止它们回流。
FongMiPlaybackRouter 保留为协议兼容/测试模型，不取代生产 ParseJob；不再新加并行解析核心。

剩余差异：上游 private fork Media3、MPV/libass、ISO/光盘导航、自定义音视频偏移/特效、完整 JS/Python/专有云盘运行时
并未整体提供。没有可重现且许可证明确的依赖，不以 stub 假装这些功能已实现。
旧业务层（NAS/WebDAV、云盘、CMS、搜索、TMDB、控件）不是旧解码核心，不删除它们以制造“整体替换”表象。

## 故障分类与有限等待

现有类型化诊断包括 SOURCE_NO_EPISODES/NO_MEDIA_URL、PARSE_INVALID_JSON/INVALID_RESULT、
AUTH_REQUIRED、PROXY 路由/健康、Header/Cookie/UA 结构证据、媒体探针 HTTP/LOGIN_OR_TEXT/INVALID_MANIFEST、
Media3 容器/解码/音频输出/DNS/TLS/网络/超时。
安全日志仅输出结构、固定码和有限计数，不输出 Token、Cookie、URL 或原始 Spider 响应。
当前没有新的 TCL 日志，因此不能把受控样本通过解释为所有第三方节点已修复。

期限已存在于成熟链路：HTTP Spider/ParseJob 15s，媒体探针约 2.8s（UI 上限4s），PlayerManager
每次缓冲15s；Java Spider 插件操作150s（包含用户授权等待120s），IPC宿主165s兜底。
阻塞插件退役与取消在真实子进程测试；返回/换线撤销旧授权，不能让过期回调恢复播放。
认证/429不盲目请求未批准备用地址；有限恢复预算覆盖过期URL刷新；UI保留重新解析、换线、其他片源与返回。
新增实际 NO_RESPONSE 媒体期限与关闭取消测试验证同一生产 PlayerManager，不用伪状态绕开解码器。

优先级保持 NAS/云盘 → 用户所选影视点 → 其他已授权影视点，直播独立。
跨影视点搜索、原名/别名及不相关结果隔离已有原始协议与真实解码测试；本阶段不改为仅搜索首页缓存。
TMDB 不生成播放地址；扫码不改变 Spider 执行授权。

## 比例修复

旧默认及旧持久化 true 都会导致 ZOOM，裁掉宽银幕和4:3画面。
现在单一完整等比模式，移除裁切选项；PlayerView仍占满窗口、padding=0，Media3 SurfaceView按内容宽高比最大化。
不拉伸、不裁切源图像，不自动猜测/删除视频内编码的黑边。
测试同时核查：真实 PlayerView 父窗口宽度、padding、真实 SurfaceView 宽高与最大化尺寸、
H.264 MP4 和 MKV 解码计数、截图四个编码边框及实际红色内画面。
16:9铺满，21:9上下留黑，4:3左右留黑。不是只检查FIT枚举值。
旧 M455/M456 ZOOM 断言与本轮用户要求冲突，替换为更强的“旧配置升级仍不裁切”和真实像素断言，没有删除比例验收。

## 阶段末回归

必须同时获得阶段一最终精确提交的完整 JVM/Lint/Debug 与 Android TV CI 成功后，才能推进阶段二。
原播放/授权/代理/Header/搜索/返回/性能门禁保持；新增 P0PlayerDeadlineTest 加入必跑类门禁。
实际 CI ID、计数与 APK 写回续跑记录和 PR；未完成之前不标记阶段一完成。
TCL 实机、第三方节点、真实高码率网络环境：待真机验收。

## 首轮回归记录

[d02ad39 设备CI](https://github.com/shawnhhh/aura-tv/actions/runs/42559008201)：245项中仅2项失败。
M456三种实际解码/SurfaceView/截图边框测试全部通过；原比例UI测试仍要求toggle裁切，
已更新为只读完整画面、无点击裁切操作、唯一播放器不重启，并在实际“单集循环”控件保留D-pad UP/展开收起断言。
新期限测试将MockWebServer.url的反向DNS构造移出主线程，修复夹具NetworkOnMainThreadException；
保留原300ms期限、4s观察上限、STATE_IDLE与释放后无旧回调断言，不放宽时限。

[f286ce2设备CI](https://github.com/shawnhhh/aura-tv/actions/runs/42560561012)：245项中244通过，
新比例、超时取消、唯一播放器及遥控器断言全部通过；仅既有扫码截图解码出现NotFoundException。
将原5秒等待“节点存在”强化为同一5秒内真实截图必须可解码且为HTTP模式，
保留后续真实电视配对/HTTP保存/自动刷新全部断言；没有拿原始生成字符串代替截图，也未增加超时。
