# M4.37 影视资料刮削

基线已具备 TMDB Token 密码输入、官方 API 搜索/详情、海报/背景/简介/评分/演职员、正负缓存及来源回退。此版本补齐非括号 4K、HDR10+、Dolby Vision、杜比、字幕与国语标记清洗，提取独立年份和来源类型，保留续集、季号与作为片名的 1917、2001 和 2049 等数字。

匹配仍要求规范化片名相等。多个同名作品缺乏年份时不自动选择，保留来源图片。手工纠错通过 `TmdbManualMatch("movie" 或 "tv", TMDB数字ID)` 仅访问官方作品详情；记录与来源身份绑定、持久化到应用私有目录，文件名为 SHA-256，不含播放地址或 Token。返回的作品 ID 必须与用户指定 ID 一致。清除纠错可恢复自动匹配。详情界面可接入 `MetadataCorrectionDialog`，支持遥控器选择类型、输入 ID、保存、取消与清除。

接线：`ArtworkMetadataResolver.manualMatch(film)` 获取纠错；`setManualMatch(film, matchOrNull)` 保存；恢复该片 `FilmMetadataEnrichment.restore` 后 `lookup(film, token, forceRefresh = true)` 真正重新刮削。全局重刮也应传 `forceRefresh=true`，否则14天正缓存仍然有效。普通增量 lookup 使用缓存，网络临时失败不写入“无匹配”负缓存。所有调用均应在 IO 协程执行，不等待刮削再进入播放器。

刮削成功采用官方标题和展示资料；原始标题、图片与简介可恢复。Film ID、Spider/CMS ID、集数与线路、播放链接未被改写。不会调用影视仓扫描或 Spider 运行时；不会逐个扫描12个影视点。官方 CDN URL 限定 `image.tmdb.org`。合法 TMDB 使用与署名仍遵循其 API 条款；不内置 Token。

原创自动测试覆盖清洗/年份/类型、MockWebServer 的真实 HTTP 请求与 Authorization header、官方资料替换、播放身份不变、恢复来源、跨 Resolver 缓存、强制重刮、手工纠错持久化与错误ID、同名歧义及网络恢复。无真实 TMDB 账户时不能声称外网 API/商业图片授权已验收。

季集结构回归：统一官方标题仅影响展示，分组的季号、特别篇/OVA排除、稳定分组身份从 `originalDisplay?.title ?: film.title` 提取，原季2/3线路保持对应关系；来源季名仍可本地搜索。纠错 Resolver 构造应传当前 `catalogScope`，例如 `ArtworkMetadataResolver(cache, catalogScope = catalogScope)`，并在 scope 变化时重建 Resolver。手工匹配文件key包括scope和原始来源身份的长度前缀SHA-256，防止两个影视点重号同名串用纠错。

详情请求竞态：Spider IPC 不承载 TMDB 富化字段，CMS 详情也可能携带旧的 TMDB 标记并覆写海报。因此所有详情完成回调应以列表里当前 Film 调用 `FilmMetadataEnrichment.mergeSourceDetails(current, details)`。当前已刮削时，仅接受来源的最新 episodes/playbackEpisodes，补充缺失的原始 Spider/CMS ID；展示资料和原始季标签保存不变。当前已停用刮削、旧请求仍携带 TMDB 标记时不恢复过时资料，只接收最新选集。双方均为原始来源资料时正常采用最新详情。不同 Film ID 的延迟结果直接忽略。原创回归使用真实 Spider JSON IPC 与 CMS detail codec，覆盖详情返回晚于刮削、CMS混合旧标记、停用后旧请求、来源正常更新及身份隔离。
