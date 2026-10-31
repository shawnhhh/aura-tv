# HERRIN M4.59 — TVBox/FongMi JSON 解析播放链路

## 技术对照

以 FongMi 官方 [Spider 文档](https://fongmi.github.io/TV/spider/) 与 [配置字典](https://fongmi.github.io/TV/config/) 为准，TVBox 依序执行 `detailContent` → `playerContent` → 按 `parse/jx/playUrl` 决定直接播放或调用配置 `parses` → 最终 Media3。此前 Herrin 多数情况仅支持第一条直接媒体链路，遇到 `https://.../watch.html` 会将 HTML 交给播放器或报告不可播放。

## 本次实现

- 从**用户已应用的单仓**解析 `parses` 中的 `type=1` JSON API，保留名称、`ext.flag`、UA/Referer。WebView type=0、JAR 解析 type=2/3、聚合 type=4 不会伪装成已经支持。
- CMS 播放前优先使用匹配线路 flag 的 JSON 解析器；没有 flag 时仅对明显 `.html/.htm` 观看页触发。直接 `.m3u8/.mp4/.mpd` 不发送到第三方解析器。
- Spider 的 `playerContent` 返回 `parse/jx=1` 但不含直链时，允许通过本次已应用的解析器得到 URL；带 `playUrl=parse:解析器名称` 时按名称匹配。
- 请求只访问用户配置过的 JSON 解析 API，且仅将编码后的源页面地址拼接在 parser 前缀后；限制响应 64KiB、单次 7+7s 超时、HTTP 200、拒绝 HTML 响应和非 HTTP(S) URL、不跨解析器向媒体服务器传递 Cookie 或 Authorization。
- 成功后使用原始播放器的 Media3，失败有清晰的解析报错和“重新播放/返回选集”，改变选集或重试重新解析，防止复用旧签名。
- 恢复上轮 M4.58 破损的 Kotlin 测试文件；不改普通 HTTPS 授权目录的限制，也不自动执行新 JAR。

## 自动化验收

- JVM：解析器配置过滤、线路 flag 与直接媒体选择、URL 编码、JSON 内嵌 URL、格式提示、敏感头隔离、网页和 HTTP 授权拦截。
- Android TV emulator 必跑 `M459JsonParserPlaybackTest`：本地 JSON API 返回本地原创合成 MP4 媒体 URL，Media3 真实解码超过 3 帧；继续执行 M458 CMS HTTP、网络失败恢复、字幕、远控等全量用例。
- 检查当前 PR 最新 HEAD 的 Debug/JVM/Lint/APK + Android TV API30 全量测试，不能用早期绿色 CI 代替。

## 尚未实现/不能承诺

- 依赖真实 WebView JavaScript、浏览器网络嗅探、JAR type2/3、DRM、网盘扫码授权的路线**尚未完成**；绝不把解析网页 HTML 误报为播放成功。
- 此测试仅验证受控原始媒体。不声称第三方不受控资源在 TCL T7L Ultra 真机上已解码出帧；真实验证需要用户授权并实际执行的 ARM 真机测试。
- 严格保持 Draft PR，不自动合并主分支；第三方 Spider 二进制必须由用户主动信任。
