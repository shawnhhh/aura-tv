# HERRIN M4.2 — TVBox 多仓/单仓直播、标准 CMS 影视 JSON 与缓存

## 目标与入口

1. 在「设置 → TVBox 多仓 / 单仓 / IPTV」粘贴 TVBox 单仓或多仓地址，手动开启本次 HTTP 授权（若地址为 HTTP），点「读取配置」。
2. 多仓出现可选择仓库按钮；单仓出现标准 CMS 影视接口的「浏览」按钮及直播频道入口的「导入」按钮。
3. 可以直接填写 M3U、TVBox TXT 或内嵌频道 JSON 的直播源地址导入。导入前校验格式及媒体链接；已选频道私有持久化，重启后离线读取快照，不再联网加载仓库。
4. 源 URL 如果只返回 JPEG/HTML/错误，且之前验证并缓存过真实单仓/多仓 JSON，则回退到该 URL 的本机缓存。也可从已有 TVBox 的备份中复制 JSON 粘贴至「导入并缓存 TVBox JSON」，保存前需要提供原始来源 URL 以做严格隔离。
5. 标准 CMS JSON 接口会追加 `ac=detail&pg=1` 请求，并读取顶层 `list`、`vod_name`、`vod_id`、`vod_play_url` 等字段。带 HTTPS 播放地址的条目可通过现有播放器播放；HTTP 视频与需要专用解析器的播放线路不自动升级或执行。

## 安全边界

- 仅接受用户手动提供或配置中明确声明的 HTTP(S) 链接；HTTP 每次启动必须再次明确许可，远端配置不会开机自动联网。
- 不执行 JAR、Spider、JavaScript、Python 或任意第三方脚本；不开代理服务；不内置未授权版权媒体。
- TVBox 配置 1MB 上限，频道列表 512KB 上限，最多保存 250 个频道。禁止非 HTTP(S)、含用户凭据或片段的 URL；禁止跨地址重定向。
- 仅在用户确认的 HTTP 授权下下载直播列表；自动保存缓存/持久化已选频道，不上传至服务器。
- 如果远端本身没有返回 JSON 且本机未存在对应 URL 缓存，HERRIN 无法凭图片生成源配置；请从原 TVBox 导出历史缓存 JSON。

## 自动化验收与剩余限制

- JVM：TVBox 内嵌直播、M3U 分组、TVBox TXT、恶意 scheme 拦截、标准 CMS JSON 与 HTTPS 播放线路解析。
- Android MockWebServer：HTTP 授权、直播 TXT 实际响应、按 URL 的 JPEG 后备缓存、用户 JSON 手动导入、标准 CMS JSON 实际响应、私有直播缓存读写。
- 最终可用性还需在用户 TCL T7L Ultra 上复测直播播放、具体源接口网络可达性和电视解码能力。
- 不支持 TVBox 的全量扩展插件、加密配置或需要专用解析库的影视站；这些能力不能通过单纯 JSON 解析保证兼容。

## 饭太硬专项

用户指定 `http://www.饭太硬.net/tv`（IDN 规范化为 `http://www.xn--sss604efuw.net/tv`）。历史外网响应 `HTTP 200`，但实际是约 20KB 的 JPEG 图，而非 JSON；需要在最终开发版本再次请求并记录 HTTP 状态、MIME 与实际文件类型。若仍为图片：属远端响应/出口差异；HERRIN 使用本地 JSON 缓存兜底，但无法无中生有生成内容。
