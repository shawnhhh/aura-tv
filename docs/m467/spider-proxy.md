# M4.67 Agent 1：Spider / Proxy 移植与验证

基线 `cd6deb3d116969f36e451be6ba6dd8649dc4b23f`，独立分支 `codex/m467-spider-proxy`。源码基准是官方 FongMi/TV `fongmi` 分支实际 fetch 的 `5e191aaced194e5cac5668cc91908ecb65336222`，不采用 TVBox 的其他分支作为替代。下文路径相对各自仓库；宿主源码位于 `android-tv/app/src/main/java/`。

## 实际修复

1. 静态 `proxy://` 规范化时加上宿主会话标记，随后播放结果再次 `rebind` 原来会把自己已签发的静态标记判为不合法。现在只允许当前端口、当前会话的私有标记进行幂等转换，伪造/旧会话标记仍拒绝。HTTP `/proxy?do=jar` 不含 `siteKey` 时也按上游走静态 JAR 回调。进入静态回调前移除宿主补入的 siteKey，保留真正的上游入参语义；无静态实现时返回错误，禁止偷偷调用实例代替。
2. 原版JAR将 `Proxy.getUrl(true)+"?do=jar"` 拼接到带会话查询串上时，会产生双问号；仅在精确当前capability前缀匹配后修复该分隔符。直接HTTP无siteKey也进入当前授权JAR静态回调，HLS内部链接不需要经过playerContent再规范化。错误token仍403。
3. 直接移植上游 `Proxy.createResponse`、`addHeaders`、`closeFailedStream`、`markError`、`toStatus` 和 `ProxyStatus` 至 `FongMiProxyResponse.java`。数组首项 Response（不要求数组长度为1）、状态码、InputStream、可选headers、未知合法状态及 `X-TV-Proxy-Error` 都通过这份移植代码处理。
4. 宿主传输保留随机端口127.0.0.1绑定、192位会话、站点限制、Host/Origin校验、有界请求头与4并发/24排队。保留401/403/416/5xx等真实上游状态；HTTP 307/302等允许安全HTTP(S) Location，拒绝协议相对地址、URL用户凭据及loopback跳转。代理本身没有联网转发入口。
5. 允许媒体/条件请求头及媒体响应头；不把 Cookie、Authorization、Set-Cookie、认证挑战和未知私有头转到别的会话或主进程。移植的原始响应核心先执行，随后由宿主安全边界过滤。Range/Content-Range不转Int，不以512MiB限流。退出时关闭活动InputStream、Socket和监听器，可解除阻塞的媒体读取。
6. 严格按本次真实上游 `JarLoader.initDex` 传播失败Init根因（仅缺少Init类可忽略）。先建立代理再执行Init，失败关闭代理和站点网络。固定分类 `SOURCE_SPIDER_INITIALIZATION` 仅来自宿主实际初始化阶段，根因沿用脱敏器；RuntimeModule缺失映射503，不暴露异常原文。取消和VM致命错误继续抛出。
7. Worker调用Agent 2的 `FongMiParsePlayback.resolve`，替换旧的串行聚合实现；保留已有播放数据接口。传递config VIP flags、站点headers/click和活动DEX loader。不修改影片详情、TMDB、UI、选集或选线交互。

## 上游 → 宿主 → 状态 → 自动化用例

| 上游文件/方法 | 宿主文件/方法 | 状态 | 自动化用例 |
| --- | --- | --- | --- |
| `app/.../server/process/Proxy.java#createResponse` | `tv/herrin/app/FongMiProxyResponse.java#createResponse` | 直接移植，包名/可见性/Nano.error宿主适配 | `M467SpiderProxyTransportTest.upstreamResponseFactoryKeepsObjectIdentityAndMarksOriginErrors` |
| 同文件 `addHeaders` / `markError` / `toStatus` / `ProxyStatus` | 同名方法/类型 | 直接移植 | 上述工厂测试；`preservesOriginHttpErrorsAndSafeRedirectWithoutOpeningNetworkProxy` |
| 同文件 `closeFailedStream` | 同名方法 | 直接移植 | 工厂错误路径；全量JVM回归 |
| 同文件 `doResponse` 的params+headers合并 | `SpiderLoopbackProxy.parse` / `serve` | 宿主安全适配：仅当前会话，过滤凭据与私有头 | `forwardsConditionalAndMediaHeadersWithoutSessionOrCredentials`，既有`SpiderLoopbackProxyTest`鉴权用例 |
| 同文件 `doResponse` RuntimeModule.MissingException | Worker `proxyErrorStatus` → `SpiderLoopbackProxy.serve` | 宿主安全适配：503保留，异常文本脱敏 | 初始化/运行时现有测试；集成RuntimeModule测试 |
| `app/.../api/loader/BaseLoader.java#proxy` | `SpiderProxyPlayback.rebaseUrl`，`SpiderLoopbackProxy.serve` | Java `siteKey`实例 / 无siteKey静态分流，严格站点授权 | `SpiderLoopbackProxyTest.staticAliasSurvivesNormalizationAndSecondPlaybackRebind`，Android `SpiderLocalProxyPlaybackTest.legacyFongMiNoSiteKeyCallsStaticProxyHandlerNotInstanceHandler` |
| `BaseLoader.getProxyUri` 的proxy://转换 | `SpiderProxyPlayback.normalizePlayerResponse` / `rebaseUrl` / `rebind` | 安全最小适配，支持旧端口重绑、静态分流幂等 | 同上；`staleTvBoxPortIsReboundToActiveSpiderProxyInsteadOfRefusedConnection` |
| `app/.../api/loader/JarLoader.java#load` / `newSpider` | `SpiderJarCache.obtain`，`TvBoxSpiderWorker.browse` | 宿主DEX/ABI/已授权下载适配，原有只读JAR策略保留 | `SpiderRuntimeCompatTest`，`SpiderRuntimeCompatIpcTest`，总集成真实DEX链路 |
| `JarLoader.initDex` | `SpiderInitPolicy.blocking`，`TvBoxSpiderWorker.browse` | 同上游异常语义，代理先启动，失败关闭资源 | `TvBoxSpiderBridgeTest.init follows pinned reference loader and propagates failed bootstrap`，fatal错误既有用例 |
| `JarLoader.invokeProxy` / `proxyInvoke` / `proxy` | Worker活动DEX静态反射回调 | 当前授权JAR静态调用，不跨站点尝试其他JAR | 上述静态分流回归；总集成真实DEX首帧 |
| `JarLoader.clear` / `destroy` / `closeNetwork` | Worker `close`，`SpiderRuntimeCompat.dispose`，`SpiderLoopbackProxy.close` | 独立进程/会话生命周期适配，关闭全部宿主依赖 | `fourHlsSegmentsRunConcurrentlyAndCloseReleasesBlockedInputStream`；既有`SpiderRuntimeCompatTest`清理用例 |
| `catvod/.../Proxy.java#set/getPort/getUrl` | 原包同名`Proxy.java` | 已有源级ABI保留；仅loopback+随机会话为必要安全适配 | `SpiderLoopbackProxyTest.sessionUrlIsUsableAndCanBeInvalidated` |
| `catvod/.../crawler/Spider.java#proxy/proxyUrl/getProxyUrl` | 原包同名`Spider.java` | 现有上游ABI和旧版方法兼容保留 | `SpiderRuntimeCompatTest`/IPC，静态与实例真实HTTP用例 |
| `catvod/.../net/Net.java` 网络/会话API | 原包同名`Net.java` + `SpiderHttpSessionHost` | 基线已有移植保留，M4.67未替换cookies/授权实现 | 现有`M427SpiderHttpSessionHostTest`、`PlaybackSessionIsolationTest`、`SpiderPasswordAuthorizationTest` |
| NanoHTTPD InputStream/chunked传输 | 现有`org.nanohttpd:nanohttpd:2.3.1` + `SpiderLoopbackProxy.serve` | 真实Response ABI；有界socket运输为安全宿主适配 | `rangeSeekAcross2GiB4GiB8GiBOf10GiBVirtualMovieReturnsRealBytes`、`continuouslyStreamsBeyondFormer512MiBLimitWithBoundedReadBuffer` |

## 执行证据

独立JVM使用实际生产 `FongMiProxyResponse.java` 和 `SpiderLoopbackProxy.kt` 编译运行，未使用HTTP实现替身。2026-11-01日志见 [spider-transport-test.log](spider-transport-test.log)：**7 tests，全部通过**。513MiB+37字节完整经过实际HTTP持续读取；10GiB逻辑文件在2/4/8GiB位置实际返回15次65536字节窗口。这个测试不分配10GiB文件，也不声称传输完10GiB或真实4K解码。四并发测试通过屏障保证四个回调实际同时进入。`originalJarProxyUrlQuestionMarkConcatenationUsesAuthenticatedStaticCallback`覆盖旧版JAR拼接方式和错误token拒绝。根集成将运行全部既有JVM/Android用例和真DEX→Media3首帧；本报告不预先声称其通过。

正常Gradle复现：在集成分支`android-tv`执行 `:app:testDebugUnitTest --tests tv.herrin.app.M467SpiderProxyTransportTest --tests tv.herrin.app.SpiderLoopbackProxyTest --tests tv.herrin.app.TvBoxSpiderBridgeTest`。分支Worker有明确Agent 2接口依赖（`FongMiParsePlayback`、`TvBoxSite.click`），独立分支未包含其实现，不应单独宣称完整Android编译成功。

## 许可和边界

- FongMi/TV GPL-3.0原许可证SHA256 `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`，与仓库 `android-tv/LICENSE-GPL-3.0.md` 完全相同；保留全文。直接移植文件新增来源、上游SHA及修改说明。
- 上游`Proxy.java` SHA256 `c63fb9b69cbce228097e9bc959532534720d146a8605a35c89e12e82e289e5f9`；`JarLoader.java`为`7e3421146094537897c2bcc443169fdd9a8aaa3060ee0afefb01b63eba51291c`；`BaseLoader.java`为`368163332cc722d9b3cd0e395ed784ce4aa67a89a01f77f7e9e01f1b5db93078`。
- 本Agent未引入native库、抓取器、商用SDK或不明AAR。NanoHTTPD 2.3.1（BSD-3-Clause）为已有依赖；保持该依赖版权/再分发义务。Net的OkHttp等依赖沿用基线，不改变其许可。宿主衍生发行仍需提供GPL对应源码与构建材料。
- 不称整个FongMi BaseLoader/JarLoader已逐字复制。多JAR缓存/跨JAR静态尝试、JS/Python `do=js/py`属于额外运行时/授权边界；当前Java活动JAR完整静态/实例播放路径不跨授权会话。外部签名运行时不能由空实现代替。
- HTTP POST文件上传未开放：播放器媒体GET/HEAD已实现，未知HTTP方法返回405；这是宿主授权面适配，不能声称是完整任意HTTP服务。HEAD不读取body；生成的chunked响应返回长度0，未从NanoHTTPD私有字段反射固定长度。`releasePlayback()`完整释放Worker，Remote保存授权源并在下次播放重建；IPC/Main实际退出连接由Agent4负责。HTTP标准扩展/私有头使用白名单，凭据不出会话。以上差异需在总体“完整上游行为”验收中明确列出，不能隐瞒。
- SOURCE_PROXY_UNAVAILABLE的这次根因已通过静态重绑定回归覆盖；不把mock媒体传输成功、模拟器首帧成功等同TCL第三方真实片源成功。
