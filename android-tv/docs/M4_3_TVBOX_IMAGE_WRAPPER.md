# HERRIN M4.3 — 图片伪装的 TVBox 单仓配置

## 根因调查（2026-10-27）

真实地址：`http://www.饭太硬.net/tv`，规范化 IDN `http://www.xn--sss604efuw.net/tv`。

- HTTP 200，响应头 `Content-Type: image/x-ms-bmp`，下载实际格式为 100×100 JPEG，20,063 字节。
- 同域名根站导航页公开了新的 `.cc/tv`、`fty.xxooo.cf/tv` 和第三方 `.bmp` 入口。请求其中 `.cc` 与 `.bmp` 的内容时，发现与原路径完整 SHA-256 相同；`.cf` 入口本地出口出现 503。
- 重要发现：JPEG 的 EOI (`FF D9`) 位于第 2,401 字节处，后面另附 17,662 字节文本，格式为 `DYxAlYOt**` 加 Base64 数据。
- 对 Base64 部分解码后，是有 `spider`、`sites`、`lives`、`hosts` 等字段的 TVBox JSON。原文本包含以 `//` 开头的注释，严格 JSON 解析会失败。只在字符串外移除注释后可正常解析；当前样本计数：49 个影视站点、6 组直播。

**更正早期判断：** 返回 JPEG 并不必然意味着配置已失效。此特定地址属于图片末尾附加 JSON 的封装格式，是 HERRIN 缺失的兼容能力。不能因此证明已有 Spider/JAR/JS 站点可在 HERRIN 内直接播放。

## 实现

1. 网络加载时以最多 1MB 的有界读取得到完整字节，即使 MIME 是 `image/*`，仍检测 JPEG 开头/EOI/尾部格式。
2. 只有检测到 4–64 个字母数字标记、`**`、合法 Base64 时才解码；要求解码后为 JSON 对象，且符合已有 TVBox `sites/lives/urls` 架构。
3. 清理 JSON 字符串以外的 `//` 和 `/* */` 注释，不破坏字符串中的 `https://` 或转义字符。
4. 保存清理后的有效 JSON 到私有 URL 绑定缓存，后续离线回退；从不执行或下载 Spider/JAR。
5. 普通 JPEG、恶意尾部、损坏 Base64 仍返回明确的错误。

## 自动化验收

- JVM 合成 JPEG 尾部 + Base64 + 注释测试，与原始 TVBox 单仓解析器、直播元数据联合验证。
- Android MockWebServer 返回 MIME `image/x-ms-bmp` 但体内有真实 JSON 尾部的图片，端到端验证单仓解析及之后的缓存恢复。
- 实际饭太硬源取样已通过独立字节级验证，不向仓库提交第三方源配置、Spider 或任何未经许可的视频资源。

## 尚不支持

影视 `type:3` `api:csp_*` 需要对应 Spider 运行时，M4.3 只恢复配置本身，不能声明影视抓取和播放功能已完成。自定义加密尾部、任意脚本、混淆 JAR、代理流和需专有 AAR 的站点没有纳入自动执行范围。
