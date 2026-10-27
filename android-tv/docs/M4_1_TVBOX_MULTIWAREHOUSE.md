# HERRIN M4.1 — TVBox 多仓与单仓配置入口

目标设备：原生 Android TV（TCL T7L Ultra）。这是 M3.9 基础上的独立 Draft 开发，不合并 `master`。

## 使用说明

1. 打开 **设置 → TVBox 多仓 / 单仓配置**。
2. 粘贴你有权访问的多仓 URL（JSON 根对象含 `urls`），或者直接输入单仓 URL（含 `sites`、`lives`）。
3. HTTPS 默认允许。若输入 `http://www.饭太硬.net/tv` 这类中文域名 HTTP 地址，需先按 **本次允许 HTTP** 再按 **读取配置**。应用把国际化域名转换为 ASCII/Punycode 发起请求；再次打开应用不会自动获取 HTTP。
4. 若解析成功，遥控器选中多仓列表中的名称，按确认进入单仓安全预览；可看到站点名称、外部引擎提示、直播组。
5. 如果某组直播的地址是直接的 **HTTPS M3U/M3U8**，可手动导入直播模块，并在那里检查是否能加载实际频道。只有用户已获授权的流应被播放。

## 格式示例

多仓：

```json
{"urls":[{"name":"我的一号仓","url":"https://example.org/one.json"},{"name":"我的二号仓","url":"http://example.org/two.json"}]}
```

单仓：

```json
{"spider":"https://example.org/engine.jar","sites":[{"key":"demo","name":"示例站","api":"csp_Demo"}],"lives":[{"name":"已授权频道","url":"https://example.org/playlist.m3u"}]}
```

以上均为**格式示例，不提供实际影视站点或可执行引擎**。

## 安全边界与已知限制

- JSON 下载上限 1 MiB，超时 8 秒，最多取 100 个单仓、300 个站点、100 个直播组；不跟随 HTTP 重定向，不接受 HTML 或图片响应。
- 配置可能是 HTML、验证码、图片或过期 URL；此时明确提示不能解析。之前从本环境请求 `http://www.饭太硬.net/tv` 得到的内容并非有效 TVBox JSON，因此它**不能作为可靠的回归样例**；各网络环境可能返回不同内容。
- Android 使用 `usesCleartextTraffic` 使明确授权的 HTTP 元数据抓取可行。**HTTP 不加密，可被中间人更改**。应用原有影视目录、海报、真实播放、M3U 与 EPG 仍只支持 HTTPS。
- TVBox `spider`、`api`、`jar`、JS 规则、加密配置、自定义接口以及 `lives` 内部嵌套线路不会执行或自动加载。元数据预览 ≠ 支持影视检索、站点解析或播放。
- 保存最后一次成功读取的根配置地址是为了便于再次输入，不保存 HTTP 同意状态、不在启动时自动抓取，不内置来源不明的仓或影片。

## 验证

`TvBoxWarehousesTest` 使用完全离线的虚构 JSON 验证多仓、去重、中文 IDN、非法 URL、单仓和引擎隔离。`TvBoxWarehouseIntegrationTest` 用本机 `MockWebServer` 验证用户拒绝/同意 HTTP、两级获取、重定向、伪装图片和 1 MiB 限制。`HerrinUiSmokeTest.tvBoxMultiwarehouseRequiresConsentThenNavigatesWithRemote` 通过 Android TV Activity 的方向键/确认键操作设置页，并检查两级读取和元数据预览。

离线单元、云端 Gradle 构建与 API30 模拟器验收状态，以对应 PR 的 GitHub Actions 为准。真实 TCL 电视连接、不同运营商 DNS/中文域名，以及第三方源合法授权和有效性需要在实机与真实网络下额外验证。
