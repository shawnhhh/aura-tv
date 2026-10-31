# HERRIN M4.58：普通非网盘 CMS/Spider 播放兼容性

## 故障与修复

- **普通 CMS HTTP 片源到达播放器前被拒绝**：目录解析允许用户开启 HTTP，但主播放界面以前统一调用只接受 HTTPS 的 `LicensedPlaybackResolver`。如今只在已选中的 TVBox CMS 条目（`cmsVodId`）走 `TvBoxCmsPlaybackResolver`；必须仍遵守用户的 HTTP 开关，普通 JSON 授权目录仍严格 HTTPS。
- **Spider 解析出的签名无后缀视频被误认为网页**：当插件返回 `parse=1`/`jx=1`，只有路径本身显示 `.m3u8/.mp4/.mpd` 或插件明确声明受支持 `format`（HLS/DASH/MP4）时交给 Media3；未知网页解析入口仍明确拒绝，不伪造媒体地址。
- **Spider `url` 数组格式**：支持单元素 URL、纯 URL 数组及原先的清晰度名称/URL 交替数组，仍对每项进行 HTTP(S) URL 安全校验。
- **跨站点请求头**：CMS 可带 `User-Agent` 和 `Referer`，但不向媒体 CDN 发送来源站点 `Cookie`、`Authorization`；未知授权/网页解析仍需来源合法提供正确媒体链接。
- **用户提示**：普通播放网络异常不再误提示必须登录网盘。

## 自动验收

- JVM：HTTP 同意开关、选择正确影片/集数/线路、HTTP/HTTPS 策略、标头隔离、Spider 带 MIME 的无后缀 HLS、URL 数组与网页拒绝。
- Android TV 全量仪器测试强制包含 `M458CmsHttpPlaybackTest`：用本地 MockWebServer 提供原创合成 MP4，经普通 CMS 选集解析出 HTTP 地址，进入 Media3 并实际解码至少 4 帧。不可把“只是 HTTP 200”当作播放成功。
- 测试不涉及第三方云盘或不受控影片网站。请以本分支最新 CI 的结果为准；没有在 TCL T7L Ultra 真机验收前不得宣称饭太硬真实影片播放已成功。

## 仍有的边界

本次解决**可播放的普通媒体直链**。需要外部 JX 网页解析、动态签名或受限制播放器接口的站点仍需要其被授权的解析运行时和正确配置。不会通过猜测影片地址、绕过 DRM、盗链或伪造视频 URL 处理。
