# M4.20：TVBox 与 HERRIN 解析链差异及真实验收边界

对照 FongMi 官方 [Spider 接口](https://fongmi.github.io/TV/spider/) 与 [配置字典](https://fongmi.github.io/TV/config/) 核对现有代码。下列项目是**在源码中确认的兼容性缺口和本轮修复**；并非未经 TCL 实机确认的“饭太硬全部站点均可播放”声明。

| 环节 | 先前 HERRIN 行为 | 本轮修复 |
| --- | --- | --- |
| CMS 详情 | 只访问 `ac=detail&pg=1` 目录；若目录不带 `vod_play_url` 就无选集 | 目录保存源站 `vod_id`；进入详情后按需请求 `ac=detail&ids=<编码后的ID>`，保留原有签名 query；不批量抓取所有影片 |
| Spider VIP flags | 总是 `playerContent(flag, id, emptyList())` | 从 TVBox 根配置读取 `flags`，经独立进程传递给 `playerContent` |
| Spider 播放请求 header | 只读取 `playerContent` 返回的 `header`，丢弃配置 `Site.header` | 解析并跨进程传递站点 `header`，当播放结果 header 为空时整体回退到站点 header；不逐项合并 |
| 长剧集与线路 | Spider 只取前 99 集/8 条线路；CMS 只取前 99 集/8 条线路 | Spider 最多 300 集/12 线，CMS 最多 300 集/12 线；Spider IPC 对体积异常的响应保留上限，不使整份详情消息超出 Binder 限制 |
| CMS URI | 已编码的 query 用 `URI` 组件构造可能二次编码（例如 `%2F` 被变成 `%252F`） | 保留 raw query、替换过期 `ac/pg/ids`，仅编码新传入的 `vod_id` |

## 尚未打通的 TVBox 能力

1. `parse=1`、`jx=1`、网页播放页不是 Media3 可直接读取的媒体文件。TVBox 配置 `parses` 包含 WebView、JSON 解析接口、JAR 和并行解析等多类实现；HERRIN 本轮仍只允许明确返回的 HTTP(S) 媒体直链。不能把网页地址伪装成可播放 URL。
2. TVBox 的 Spider `proxy/localProxy` 本地 HTTP 转发和网页嗅探、解密专有 JNI、QuickJS/Python 运行时、站点类型 0(XML) 和类型 4 扩展 HTTP 协议未达到一一等价。部分站点仍会显示“无源”或需外部解析器。
3. 合法源可能仍返回 HTTP 403/404、TLS 失败、设备不支持的编码格式、DRM 或过期播放鉴权。需要查看播放器实测 Media3 错误码及 HTTP 状态，再对具体节点定位。**单凭识别出 80 个片名或单测通过，不代表 TCL 已输出视频帧。**

## 分层验收

- JVM：解析配置→列表→根据 `vod_id` 读取详情；更多集数/线路；flag、header 的保留与回退；恶意 URL 不得成为直链。
- Android MockWebServer：确认真实第二个详情 HTTP 请求，query 不重复编码，播放集数完整。
- Android TV 模拟器：播放已授权公开测试媒体，并校验实际首帧。
- TCL T7L Ultra：安装对应版本后对用户自选站点采集具体播放错误码（不记录 URL、Cookie 或 token），只有成功出帧才标记真实源播放通过。
