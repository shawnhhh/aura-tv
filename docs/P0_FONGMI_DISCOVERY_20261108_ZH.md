# P0 多站点发现：FongMi 协议对照与验收检查点

## 基线与证据边界

2026-11-08 开始核对：Draft #84，远端/本地 HEAD 均为 `689f4da139fe04ca97c9a79aec96920f135f9c06`。该基线 Debug 与 TV CI 均成功；不将基线的成功冒充本轮成功。本轮继续同一分支，不合并、不强推、不重置设置。

实际读取上游 FongMi/TV 当前提交 `4512b4c9f82534ff66acfde06ccdd68f35b074f0`，仅作为搜索架构参考。现有 43 个固定 FongMi 播放文件未替换或更新，不复制上游新播放器。

没有用户的饭太硬配置、奶酪 JAR 授权样本、TCL 连接或故障 logcat。不能对比该站真实 HTTP/JAR 响应，不能声称已修复奶酪特定崩溃或完成第三方联网播放。

## 确认的源码差异

| 阶段 | FongMi 实现 | 原 Herrin 路径 | 本轮实现 |
|---|---|---|---|
| 搜索调度 | SiteViewModel / ViewModelSearchRunner，按站任务，独立超时、epoch 取消、逐站发布 | 8 秒包住个人源、CMS、Spider 初始化、搜索、详情、解析和媒体探测，首个验证线路才发布 | HerrinSourceDiscovery，个人源短预算后首选先执行；其余 CMS 并发 4，Spider 初始化+搜索整个事务串行；无整体 8 秒强制终止 |
| Spider | `searchContent(keyword,false)`，返回 list 附 Site | 相同调用，但两层严格标题/年份/类型过滤，随后要求选集和可播放媒体 | 原始 VOD 结果先保留；搜索层不请求详情/playerContent/媒体，不用 TMDB ID 替代 VOD ID |
| HTTP API | SiteApi 的 type=4 搜索 `wd/quick/extend`，详情 `ac/ids`，播放 `play/flag/extend`；长 ext 用 POST | 未保存 type，HTTP API 全部作为 CMS `ac=detail`，漏 ext，opaque 集 ID 被 HTTP URL 过滤丢掉 | 保存 apiType；type=4 正确 GET/POST、保留 opaque 集 ID；点击播放后调 HTTP playerContent 和原 FongMi ParseJob |
| CMS | type=0 XML、type=1 JSON，根据类型解析 | 仅 JSON、固定 detail 动作 | 增加禁 DTD/外部实体的 RSS XML 数据适配和 videolist；JSON 原路径保留 |
| 详情身份 | 每个 Vod 附 Site，详情用 provider id | 原 CMS 已保留 cmsVodId，Spider 详情取 list[0] | 发现 key = root/site/API/JAR/ext 身份 + 原始 VOD；详情优先精确 VOD；明确不同 ID 拒绝，兼容单项未回传 ID 的 Spider |
| 展示 | 站点结果可以先于可播放地址展示 | UI 没有搜索命中层，成功 0 可能只是媒体验证 0 | 详情页增加按站分组的真实条目；单站详情失败保留其他结果；选集不代表已出帧 |

上游永久链接：[SiteApi](https://github.com/FongMi/TV/blob/4512b4c9f82534ff66acfde06ccdd68f35b074f0/app/src/main/java/com/fongmi/android/tv/api/SiteApi.java)、[SiteViewModel](https://github.com/FongMi/TV/blob/4512b4c9f82534ff66acfde06ccdd68f35b074f0/app/src/main/java/com/fongmi/android/tv/model/SiteViewModel.java)、[ViewModelSearchRunner](https://github.com/FongMi/TV/blob/4512b4c9f82534ff66acfde06ccdd68f35b074f0/app/src/main/java/com/fongmi/android/tv/model/ViewModelSearchRunner.java)。

这些差异证明原宿主可能丢弃真实命中，并不证明奶酪当次请求实际返回了什么。0/44、1/44、10/44 是旧验证流水线的进度，不能据此断言其余节点无资源。

## 新链路与安全

1. TMDB 仍负责元数据。站点用标题/可用别名查询，保留 provider title、remarks、pic 和完整原始 vod_id（有大小限制，超限拒绝，避免截断成错误 ID）。影片名/年份差异不在发现层硬删；用户看到具体站点的真实条目后选择。
2. 个人媒体 → 明确首选 → 其他兼容授权来源。没有配置的个人源不建节点。节点任务最多 12 秒，排队不消耗节点执行预算；发现不中断详情交互，可暂停。已终结任务不在继续时重跑，取消中的任务保持未完成，已发现结果保留。
3. 每个来源有自己的详情回调、原始 VOD、API headers 和解析配置。读取选集不做媒体请求。切换 Spider/进程死亡后，按相同 root/site/runtime 重新激活、查回相同 VOD 再详情，绝不重新签发旧 Film。
4. 选择具体集数后才 playerContent → 原 FongMi ParseJob/Proxy → 媒体响应检查 → Media3。来源状态不将 URL 已返回称为播放成功；真正出帧由现有播放器事件/解码计数证明。失败时保留其他来源，提供重试/换线/返回选集。
5. JAR 仍受原 root/JAR 精确授权和配置 ticket 约束。导入/首选不授予执行权。仅授权当前根的 Java Spider；其他仓 CMS 可合并，未获对应授权的其他仓 JAR 不执行。未实现的 JS/Python 不纳入自动搜索总数。type=4 返回无所属授权进程的 `proxy://` 不冒充有效媒体。
6. 内存结果最多保留 24 部影片，不持久化播放 URL/响应/凭据。诊断保留阶段、耗时、有限错误码、条目数、无效/上限过滤；站点用摘要，关键词仅保留影视标题安全字符，屏蔽 URL/参数形式。现有脱敏 Spider 异常栈继续保留。

## 自动化覆盖与执行状态

新增 `P0AggregatedDiscoveryTest`：多源同名、无媒体仍可发现、优先顺序、超时隔离、44 节点取消/继续、并发上限、详情失败不删其他来源、配置版本隔离、真实 loopback CMS 原始 ID 详情、root/API/JAR 身份、Spider 错误详情 ID 拒绝。

新增 `P0HttpSpiderProtocolTest`：type=4 搜索/详情/播放参数及 opaque ID、长 ext POST、普通 CMS 不向 Media3 投递 opaque ID、解析配置隔离、XML 搜索/详情和 XXE 拒绝。

新增 TV `P0DiscoveryProtocolTest`：实际测试 DEX / IPC 两站聚合、进程死亡恢复同一 VOD；HTTP type=4 → 原 FongMi → 原创 MP4 媒体字节检查 → 实际解码帧。样本均为原创协议测试样本，不代表奶酪或第三方联网成功。

本地静态检查和 3 项播放边界 Python 回归通过。Gradle 构建尝试因沙箱 socket 禁止下载 wrapper 失败，环境没有 Android SDK/adb；完整 JVM/Lint/Debug 与 TV 模拟器通过仓库现有 GitHub CI 执行。当前本轮 CI 尚在执行，最终结果、同提交 APK 和 SHA-256 在 Draft PR 中更新，不能提前宣称通过。

## TCL 必验

使用用户合法授权的同版本饭太硬配置，选奶酪，查询小城良方；导出脱敏 source / Spider 日志，确认 init、searchContent(false)、原始/解析计数、详情 VOD、线路和选集数；与 TVBox 对比同关键词、同 root/site/JAR/ext 身份。测试多站命中、未获授权站不执行、暂停/继续、切片/电影、HLS/MP4/JSON/WebView/合法 proxy、换线/重试/拖动/续播和实际视频帧。没有真机证据，全部标记待验。

## 首轮失败保留

`d516f97` Debug run 42429308967：640 个 JVM 测试中 639 通过，`M437SourceDetailMergeTest` 的 Spider 元数据合并用例失败。共享详情样本的 vod_id 是 CMS 的 `original-cms-id`，请求却是 Spider 的 `original-spider-id`，被新增身份校验正确拒绝。将该用例的响应样本改为所请求的 Spider VOD，保留所有 IPC/元数据/原始 ID 断言；独立的显式错误 VOD 拒绝回归继续保留。没有删除或跳过失败用例，没有放宽生产校验。

新增真实 Activity 回归 `tmdbDetailStreamsBothCmsSourcesAndLoadsOnlyChosenProviderVod`：两个本地 HTTP CMS 返回带后缀和不同年份的条目，UI 展示两站真实响应；未选择时详情请求 0、媒体请求 0；分别选择后只使用对应 provider VOD 请求详情，媒体仍 0，另一来源仍保留。该测试是受控协议集成测试，不是第三方成功证据。

诊断补充：Spider IPC 回传原始列表数量与解析保留数量，宿主安全环形日志可导出；不是只在子进程内记录。节点事件包含实际 completed/total，保留 ACTIVATE_FAILED、429 等阶段/错误分类。新增回归验证失败不会污染其他来源，以及私有 URL/参数不会进入导出文本。

## 后续回归与修正

`263d16a`：646 JVM / Lint / Debug 通过；`a9d30e2`：648 JVM / Lint / Debug 通过。均不代替最终 TV 验收。首轮 TV `d516f97` 共 233 项，4 项失败：高级筛选确认、搜索上下焦点、缓存首页、最近播放 NAS。失败作业 42429309010 保留。

最近播放 NAS 的失败确认由发现/详情拆分引入：原历史入口等待自动生成选集，新发现层等待选择来源。恢复显式最近卡片续播：只对严格匹配当前影片的已授权新结果读取选集，继续交给原历史坐标映射及播放器；不保存或重放旧 URL，不放松新详情的来源身份。其他焦点/缓存失败继续采集现场，不以猜测改动已验收首页。

进一步修复：已导入其他仓的元数据在首选站任务后有界并发展开，返回的 CMS 节点立即加入当前发现任务，不再只有缓存命中或用户反复手动继续才检索。元数据读取失败和取消可继续，保留已有站点结果；其他根 JAR 不因展开配置获得授权。新回归覆盖首选先于仓读取、动态加入来源、取消/继续、站点移除清理终态和禁止下载/执行展开仓 JAR。

`2f4ddbf` TV：234 项仅最近播放 NAS 失败；`263d16a` 与 `a9d30e2` TV：235 项各有最近播放 NAS 与搜索类别确认失败。双 CMS 详情 UI、两个 Spider 新协议/出帧用例已通过。不能将这些部分通过称为整套通过。

输入故障证据：首轮 00:29:40.983 记录搜索 IME handoff、acceptingText=false；00:29:41.053 注入确认，Activity 仅收到 action=1（UP），没有 action=0（DOWN）。另一次搜索 Down 同样未进入 Activity。原代码在已结束文本连接后仍无条件 restartInput，这也被现有设置入口注释列为吞首键风险。改为仅 isAcceptingText 仍为 true 时重启，继续用原始遥控事件与全部原断言验证；不更改导航排列、视觉或焦点样式。

首轮缓存首页失败在后续完整运行中通过；目前不能确认唯一原因。增加失败时公开测试语义树输出，保留原即时断言和性能门禁。CI 证据 ZIP 由连接器取得引用，但执行环境下载该文件返回 HTTP 403，继续使用作业保留的原始日志和新增现场输出；不据此跳过测试。

跨来源选集保留：各命中的实际集号、集名、线路 ID/名称按完整来源身份存入内存布局，切换/失败/继续不清空其他来源布局；不在该布局保存媒体 URL、Cookie 或签名，重新选择来源仍读取当前详情和授权。增加对应来源隔离/继续/错误 VOD 拒绝回归。

将“聚合两 Spider → 回收工作进程 → 恢复原 VOD 详情”用例继续执行到同站 playerContent、FongMi 原生 Proxy、真实媒体字节和至少 4 个实际视频解码帧，并将该类纳入完整 TV 必跑门禁。所有数据都是原创测试 DEX/媒体，仍不是第三方源验收。
