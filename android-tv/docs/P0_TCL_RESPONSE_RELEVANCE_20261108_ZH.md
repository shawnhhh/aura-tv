# TCL 播放响应与搜索相关性专项（2026-11-08）

## 证据边界

仅收到用户列出的五条真机阶段事件，没有 TCL 完整 logcat、失败线路原始合法响应或设备连接。
`PLAYER_CONTENT_RETURNED` 是主进程 `SpiderIpc.readPlayback` 成功后的事件，**不是插件刚返回原始 JSON 的事件**。
`HEALTH_READY` 只认证 `/health` 会话，不读取实际媒体。若随后 REJECTED 且未进 Media3，说明失败处于返回之后的本机代理恢复或媒体预检阶段；不能据此归咎插件，也不能确定以下缺陷就是该 TCL 节点的唯一根因。

## P0-1 已证实的宿主缺陷及修复

1. 固定 FongMi Result 支持 `header` 为对象或 JSON 字符串。HERRIN 的直链转换只读取对象，合法字符串里的 UA/Referer 被忽略。修复两种结构，并将 `ua` 映射到 User-Agent。沿用显式响应头优先、整组站点头回退，不拼接旧 Cookie。
2. 媒体检查为凑齐 1024 字节或等待 EOF，可能在已经收到完整 `#EXTM3U` 首行后仍超时，误拒持续分块 HLS。识别完整 HLS 首行后结束预检；分片首部仍累计读取。未关闭检查，也没有把 HTML/JSON、空响应、伪造 Range 或未授权 localhost 当视频。
3. UI 将 probe 的有限结果压成布尔值并返回无代码文本，诊断最终成 SOURCE_UNCLASSIFIED。现保留 MEDIA_PROBE_*、HTTP 状态、媒体类型、有限网络原因；解析阶段保留 PARSE_*、响应 URL 结构、header 类型、parse/jx 和解析器类型。Spider 子进程的结构证据随 PLAY IPC 回传并经主进程固定代码校验后记录；不输出正文、地址、Cookie、异常 message 或插件字段名。

## 固定上游逐分支对照

读取 FongMi/TV `4512b4c9f82534ff66acfde06ccdd68f35b074f0` 的 SiteViewModel、ParseJob、Proxy；运行中的宿主 ParseJob 基于 `5e191aaced194e5cac5668cc91908ecb65336222`，仓内 43 个固定文件仍按既有清单验证。没有更新核心或冒充移植新版本。

| 分支 | HERRIN 路径与边界 |
|---|---|
| playerContent(flag,id,vipFlags) | 精确选中 PlaybackLine.title/uri；不是 TMDB id。FilmAuthority 绑定 site/runtime/vod/episode/line，拒绝跨节点或篡改选集 |
| parse=0/jx=0/playUrl 空 | SpiderVodCodec；字符串、数组、Url.values 由既有协议转换保留；本轮补字符串 header |
| parse=1/WebView | 原 ParseJob type=0 → FongMiBrowserPlayback；仍受期限、取消与格式检查约束 |
| json:/parse:/jx/VIP | 原 setParse 选择顺序；type=1 JSON，2 JAR 扩展，3 聚合扩展，4 JSON/WebView 并行；未改原分发 |
| proxy:// 与本机 /proxy | NativeFongMiRoute 转当前 site/session；保留原操作参数。健康检查不等同实际媒体可读 |
| Proxy 返回 | 上游支持 Response 或 Object[] 的状态/MIME/InputStream/headers；现有 NativeFongMiProxy 保持该 ABI |
| HTTP/HLS/JSON/HTML | JSON 播放控制响应先解析，最终媒体预检不能把控制 JSON 或登录页交给 Media3；HLS 必须存在真实文件头 |
| UA/Referer/重定向 | 修复字符串头与 ua 别名；预检跨源仍只保留 UA/Accept/Accept-Language，不扩大 Referer/Cookie/Authorization 跨源权限；本机重定向继续拒绝 |

失败保留已发现的其他站点、其各自选集和解析配置，用户可返回详情选择其他站点或换线重试。既有详情原 vod_id 校验保留；选集缓存另核对 Spider site/runtime。

## P0-2 原因及修复

发现层过去有意保留原始条目，但 UI 直接把全部条目呈为目标影片来源，未区分相关性；首个关键词返回任意条目便停止，也会漏掉英文原名或别名命中。

- 原始条目保留在 Snapshot，不篡改站点 vod_id。独立相关性：中文正式名/原名/已知 aliases 经现有 NFKC/技术后缀规范化；名称精确且年份/类型/季集兼容为确认匹配；同名年份或类型冲突、可信系列包含关系为可能相关；其余未确认。
- 沿用现有 ±1 年发行差异容忍；不因 1997/2026 同名而确认匹配。没有猜测不存在于元数据中的别名。
- 确认匹配优先显示，可能相关须用户手选；其他未确认只显示数量，保留原始数据，不自动详情或播放，并在详情动作入口再次阻止。
- 只有确认命中才提前结束别名查询；无关条目不阻止下一已知名称。
- 每次发现调用增加版本票据，旧调用即使未被取消也不能回写新任务；主线程同时校验影片、配置、查询 epoch/revision。Spider SEARCH 回传实际接收关键词摘要，与发送端比较；脱敏日志包含逐站 keyword/query_id/raw/parsed。
- 统计明确原始保留条目、确认、可能与排除，未将原始条目称为有效可播片源。原协议 raw/parsed/discarded 诊断仍保留（Snapshot 条目经过协议上限和去重）。

未发现已拿到证据能证明 TCL 的关键词编码丢失、旧缓存或插件忽略关键词；不能凭不相关结果推定其中任一原因。协议回归直接断言服务端解码后的中文与英文 wd。

## 回归与本地环境

新增 JVM 用例覆盖奥德赛/生化危机等分层、原名/别名/NFKC、同名跨年份与类型、系列差异、旧查询未取消回写、字符串 header/ua、分块 HLS 不等 EOF、精准错误码、无关结果后继续原名并保留 vod_id。
既有原始断言与性能门禁保留；原 MP4 真实帧用例增加 FongMiParsePlayback 的字符串 header 转换后再经代理字节预检与实际 Media3 解码。其余 JSON/WebView/扩展/HLS/DEX/Binder/多站协议测试继续全量执行。

本地结构检查和 Python 边界测试已通过，固定 43 文件校验通过。本机没有 Android SDK/模拟器；Gradle wrapper 首次本地尝试受目录/网络沙箱限制，故完整 JVM/Lint/Debug 与 Android TV API30 由 PR 的既有 Actions 执行，最终状态、计数与 APK 必须以同提交 Actions 为准。

## TCL 必须复验

使用本轮最终通过 CI 的 Debug APK，并核对签名兼容，不卸载清数据。
1. 原失败站点、原 vod_id、原线路 flag、原 opaque 集 ID：记录响应结构、parse/jx、解析器、代理健康和 MEDIA_PROBE 结果；检验实际首帧、音轨、拖动、续播。
2. 同节点合法直链/字符串头、HLS（分块/扩展名缺失）、JSON 解析、WebView、proxy:// 与 CDN 重定向；403/登录 JSON/HTML/过期地址须准确报错且可换线。
3. 奥德赛搜索：生化危机/后会无期/八仙过大海不得确认或自动获取选集；同名不同年代、中文/英文原名手选；快速换词和多站同 vod_id 不串线。
4. 返回其他节点重试、切换线路、后台恢复，以及既有 TMDB/首页/海报/直播。

未取得真实 TCL 日志和对应合法影视源响应，**不宣称饭太硬第三方节点实机播放成功**；原创受控样本出帧不能替代第三方验收。
