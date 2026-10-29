# M4.32 — CatVod Java Spider 本地代理兼容及真实首帧验收

## 已确认的播放链路缺口

本工程 vendored CatVod `com.github.catvod.crawler.Spider.getProxyUrl()` 使用 `com.github.catvod.Proxy.getUrl(true)` 拼接 `/proxy?do=jar&siteKey=...`。在 M4.31 与此前版本中，`Proxy.port` 初始值为 `-1`，整个 Spider Worker 启动过程中没有对它赋有效值。因而需要 Java Spider `proxy(Map)` 的站点**无法**通过该本地地址给 Media3 提供媒体；此前有些来源“目录与选集成功，但无法出画面”的现象与这个缺口一致。不能因此认定所有来源的失败原因相同。

参考 CatVod Java 官方 Spider 规范：Java 返回 `Object[]{HTTP 状态码, Content-Type, InputStream, 可选 headers}`；第一个元素是 `NanoHTTPD.Response` 的其他模式本阶段不支持。不同于 JS/Python/Node Spider，本阶段只对项目当前已接入的 Java Spider 提供兼容。

## M4.32 实际代码

- 在独立 `:spider` 进程中，于当前 Java Spider 初始化前创建 `SpiderLoopbackProxy`：OS 分配随机 TCP 端口、仅绑定 `127.0.0.1`、双工作线程与有界队列。
- `com.github.catvod.Proxy.set(port, session)` 将端口及一次性 192-bit 随机 token 发布给本进程的插件；`Spider.getProxyUrl(...)` 生成带 token、do 和 siteKey 的可读地址。**绝不监听电视 LAN 接口**。
- 只允许 GET/HEAD、`/proxy`、精确 Host、`do=jar`、当前站点 siteKey、令牌完全匹配，不接受浏览器 Origin；查询、HTTP 行和响应长度有界。
- 将代理请求的非令牌参数传给**当前** `Spider.proxy(Map)` 回调，将支持的 Java `InputStream` 以 HTTP chunked 分段写回 Media3；丢弃所有不在允许集合内的响应头，拒绝未知重定向/无效返回。
- 换站点、Worker.close 或加载失败时立即关闭监听套接字与会话、清空 `Proxy.port`；不在主进程运行 JAR，不持久化 token，不抓取任意 URL、不实现开放代理。
- Media3 原播放器、Apple TV 风格界面、M4.31 的首帧诊断，以及 Spider 原有 IPC 不发生破坏性更换。
- 构建版本 `0.4.32-alpha` / versionCode 37。

## 自动化验证

### JVM 测试
`SpiderLoopbackProxyTest` 通过真正 loopback HTTP 连接验证 Java InputStream 字节流、Site/Token/Host/Origin/Method 拒绝、响应头白名单、非法返回的 502 和关闭后会话失效。

### Android TV instrumentation
`SpiderLocalProxyPlaybackTest` 使用真实 CatVod `Spider.getProxyUrl()` 生成的本地地址，模拟 Java Spider.proxy 返回**应用原创 MP4**，让 Media3 的真实播放器下载、解码并输出视频帧，再在屏幕「查看实播状态」中确认首帧。**它需要独立集成分支 CI 成功才可宣称自动化验收通过**。

## 目前仍未完成的真机条件

- 真实 TCL T7L Ultra 的 ARM/JNI Spider JAR 及来源访问权限、实际 HTTP 状态仍需要同机验证。
- Java Spider 若动态创建需要 Activity 的组件、用户交互式网页解析、外部 DRM、JS/Python/Node 代理，仍不能由本补丁自动兼容。
- 本地代理只在来源已被用户明确选用并成功初始化后开放。由于 URL 包含运行时 token，不能记录、分享或上传播放源完整链接。
- 真机验收至少应有：同一有权使用的站点解析到有效直链/代理 URL，播放器成功收到分段/媒体字节，Media3 选中视频轨、实际输出视频帧、画面与声音正常。Dolby Vision/HDR10+/Dolby 音频输出另行验证。

## 不合并约束

本分支应单独建立 Draft PR，以上游 M4.31 Draft PR #38 为基线，不合并旧 PR 或主分支；CI 失败必须修复后才能进入真机验收。
