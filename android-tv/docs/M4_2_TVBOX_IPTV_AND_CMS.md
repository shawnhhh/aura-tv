# HERRIN M4.2 — TVBox 多仓/单仓直播、标准 CMS 影视 JSON 与缓存

## 目标与入口

1. 在「设置 → TVBox 多仓 / 单仓 / IPTV」粘贴 TVBox 单仓或多仓地址，手动开启本次 HTTP 授权（若地址为 HTTP），点「读取配置」。
2. 多仓出现可选择仓库按钮；单仓出现标准 CMS 影视接口的「浏览」按钮及直播频道入口的「导入」按钮。
3. 可以直接填写 M3U、TVBox TXT 或内嵌频道 JSON 的直播源地址导入。导入前校验格式及媒体链接；已选频道私有持久化，重启后离线读取快照，不再联网加载仓库。
4. M4.2 对远端图片/HTML/错误仅可使用已缓存的 JSON 回退；后续 M4.3 进一步识别部分 **JPEG 尾部 Base64 JSON 封装**，能够直接抽取可验证的有效配置。也可从已有 TVBox 的备份复制 JSON 粘贴至「导入并缓存 TVBox JSON」。
5. 标准 CMS JSON 接口会追加 `ac=detail&pg=1` 请求，并读取顶层 `list`、`vod_name`、`vod_id`、`vod_play_url` 等字段。带 HTTPS 播放地址的条目可通过现有播放器播放；HTTP 视频与需要专用解析器的播放线路不自动升级或执行。

## 安全边界

- 仅接受用户手动提供或配置中明确声明的 HTTP(S) 链接；HTTP 每次启动必须再次明确许可，远端配置不会开机自动联网。
- 不执行 JAR、Spider、JavaScript、Python 或任意第三方脚本；不开代理服务；不内置未授权版权媒体。
- TVBox 配置 1MB 上限，频道列表 512KB 上限，最多保存 250 个频道。禁止非 HTTP(S)、含用户凭据或片段的 URL；禁止跨地址重定向。
- 仅在用户确认的 HTTP 授权下下载直播列表；自动保存缓存/持久化已选频道，不上传至服务器。
- 普通 JPEG 没有尾随 TVBox 配置时仍不能解析；特定图片有真实的 Base64 JSON 尾部时，M4.3 可识别该结构。不能把所有图片都误认作电视源。

## 自动化验收与剩余限制

- JVM：TVBox 内嵌直播、M3U 分组、TVBox TXT、恶意 scheme 拦截、标准 CMS JSON 与 HTTPS 播放线路解析。
- Android MockWebServer：HTTP 授权、直播 TXT 实际响应、按 URL 的 JPEG 后备缓存、用户 JSON 手动导入、标准 CMS JSON 实际响应、私有直播缓存读写。
- 最终可用性还需在用户 TCL T7L Ultra 上复测直播播放、具体源接口网络可达性和电视解码能力。
- 不支持 TVBox 的全量扩展插件、加密配置或需要专用解析库的影视站；这些能力不能通过单纯 JSON 解析保证兼容。

## 饭太硬专项

用户指定 `http://www.饭太硬.net/tv`（IDN 规范化为 `http://www.xn--sss604efuw.net/tv`）。M4.2 初查为 20,063 字节 JPEG，误以为无配置。M4.3 二次取证发现完整 JPEG 约 2,401 字节，其尾部另有 17,662 字节，形如 `DYxAlYOt**[Base64]`。解码后 JSON 包含 `sites`（49 个）、`lives`（6 组）及 `spider`，但含 `//` 注释，因此必须先进行受限 Base64 解封装及注释清理。见 [M4.3 专项说明](M4_3_TVBOX_IMAGE_WRAPPER.md)。
