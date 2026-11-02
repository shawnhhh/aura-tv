# M4.67 ParseJob 移植记录

上游仓库：https://github.com/FongMi/TV ，官方 `fongmi` 分支。
核实版本：`5e191aaced194e5cac5668cc91908ecb65336222`。
宿主基线：`cd6deb3d116969f36e451be6ba6dd8649dc4b23f`，PR #75（Draft）。
独立 worktree：`/workspace/herrin-m467-parsejob`；分支：`codex/m467-parsejob`。

## 源码归属与适配边界

`android-tv/app/src/main/java/tv/herrin/app/fongmi/parse/ParseJob.java` 直接来自上述上游的 `app/src/main/java/com/fongmi/android/tv/player/parse/ParseJob.java`。
保留原始方法名、type 0–4 分派、两个线程池、latch、15 秒 deadline、AtomicBoolean 结果竞争、HTTP call 集合及停止顺序。
包名以及 App/VodConfig/BaseLoader/ParseWebView 的全局依赖被替换为 `ParseJob.Host`。这是宿主接口适配，而不是以旧的串行“类似解析器”替换调度。
`Parse` / `Result` 只投影 ParseJob 实际消费的播放字段，影片资料模型没有迁移或修改。

上游许可证为 GPL-3.0，原许可证保存在 `android-tv/LICENSE-GPL-3.0.md`；派生文件带上游路径、固定 SHA、SPDX 和修改说明。上游这些文件未声明独立个人版权头，因此没有臆造作者版权。此部分没有新增 native 二进制、私有 AAR 或需要第三方签名的模块。JSON/HTTP 仍使用仓库已有 Gson、OkHttp 与 CatVod 依赖。

## 方法映射（宿主路径均相对 android-tv/app/src/main/java）

| 上游文件 / 方法 | HERRIN 文件 / 方法 | 状态 / 必要适配 | 自动化用例 |
|---|---|---|---|
| player/parse/ParseJob.create/start | tv/herrin/app/fongmi/parse/ParseJob.create/start | 直接移植，注入 Host | M467ParseJobTest 全部 |
| ParseJob.setParse | 同名方法 | 保留 useParse→json:→parse:→type0 优先级 | type0UsesSiteClickAndParserHeaderPriority；指令专项 |
| ParseJob.resolveHeaders | 同名方法 | parser 配置非空时替换 Result 头，否则使用 Result 头 | type0UsesSiteClickAndParserHeaderPriority |
| ParseJob.execute/parseResult | 同名方法 | 保留 FutureTask、15 秒超时；Host 接收脱敏错误类型 | timeoutCancelsHttpAndDeliversOneFailure |
| ParseJob.doInBackground | 同名方法 | 保留 type=0/1/2/3/4 switch | type0/type1/type2/type3/superParse 用例 |
| ParseJob.jsonParse(Parse,String,boolean) | 同名方法 | 原调用流；Host 提供可取消 OkHttp Call；HTTP 错误不作为成功 JSON | type1RealHttpUsesDataUrlAndResponseHeaderReplacement |
| ParseJob.readJsonResult | 同名方法 | 保留 url→data.url，顶层 UA/Referer/Cookie 覆盖 fallback | 同上 |
| ParseJob.jsonExtend | 同名方法 | 保留所有 type1 的有序 jxs 与 extUrl | type2LoadsActualJarAndRetainsNeedParseWebContinuation |
| ParseJob.jsonMix | 同名方法 | 保留所有 parser 的 mixMap 和参数顺序 | type3LoadsActualJarAndPreservesMixedAbiArgumentOrder |
| ParseJob.superParse | 同名方法 | 并发启动 JSON 和 WebView，首个成功终止其余任务 | superParseRacesActualHttpAndCancelsSlowLoser；M467ParseJobInstrumentedTest.aggregateJsonFailureFallsThroughToRealWebView |
| ParseJob.jsonParse(CountDownLatch,...) | 同名方法 | 保留非致命候选失败和 countDown | superParseRacesActualHttpAndCancelsSlowLoser |
| ParseJob.checkResult(Map,...) | 同名方法 | 保留 fatal 参数语义 | type1、聚合失败专项 |
| ParseJob.checkResult(Result) | 同名方法 | 保留 JAR 返回 needParse 再进 WebView，parseHeaders 覆盖 | type2LoadsActualJarAndRetainsNeedParseWebContinuation |
| ParseJob.startWeb 3 个重载 | 同名方法 / Host.startWeb | 保留 click/header/key/from/detect 参数，主线程创建、取消时销毁 | type0UsesSiteClickAndParserHeaderPriority；Android WebView 专项 |
| ParseJob.getHeaders/getHeader | 同名方法 | 保留 parser 对象身份和大小写归一语义 | type1RealHttpUsesDataUrlAndResponseHeaderReplacement |
| ParseJob.onParseSuccess/onParseError/complete | 同名方法 | AtomicBoolean 首次完成，主线程回调后清理 | superParseRacesActualHttpAndCancelsSlowLoser；stoppedJobNeverCallsClientAndRejectsLateCompletion |
| ParseJob.deliverResult/stop | 同名方法 | 保留 finally、cancel HTTP、shutdownNow、关闭所有浏览器 | timeoutCancelsHttpAndDeliversOneFailure；stoppedJobNeverCallsClientAndRejectsLateCompletion |
| bean/Parse.get/isEmpty/getHeader/getClick | tv/herrin/app/fongmi/parse/Parse 同名方法 | 去除 Android UI selection / Diffable；播放语义保留 | type0/header/JAR 专项 |
| Parse.extUrl/mixMap | 同名方法 | URL-safe Base64（保留 padding），有序 JAR 选项不截断 | type2、type3 实际 fixture JAR |
| bean/Result.needParse/getJx/getPlayUrl/getClick/getUrl | tv/herrin/app/fongmi/parse/Result 同名方法 | 播放字段投影，保留 quality position | type2；现有 FongMiPlaybackRouterTest |
| Result.isUseParse / PlayerManager.parse | tv/herrin/app/FongMiParsePlayback.resolve | 消费当前站点全局 vipFlags 与 jx，显式 playUrl 继续支持 | Android ParseJob 专项 |
| loader/JarLoader.jsonExt/jsonExtMix | tv/herrin/app/FongMiJarPlayback.invoke | 保留包、静态 parse、LinkedHashMap 及参数顺序；ClassLoader 接受已授权 DexLoader | type2/type3 实际本地 JAR |
| ui/custom/ParseWebView.start/initSettings | tv/herrin/app/FongMiBrowserPlayback.resolve | 既有宿主 WebView ABI 适配，15 秒超时、JS/DOM、UA、Cookie、click | M461BrowserPlaybackTest；M467ParseJobInstrumentedTest |
| ParseWebView.shouldInterceptRequest/isVideoFormat | 同上 + FongMiMediaProbe.probe/isMedia | 使用真实 HTTP MIME / 前512字节确认，不能只依赖扩展名；保持 TLS 验证 | sniffingRequiresHttpMimeOrBytesAndNeverMp4SuffixAlone；browserRecognizesExtensionlessHttpMediaAndRejectsFakeMp4Html |
| ParseWebView.onParseAdd/startChild | FongMiBrowserPlayback.resolve 嵌套 child | player URL 最多5个，child禁递归、取消关闭 | 浏览器嵌套专项 |
| ParseWebView.getResultHeaders/stop/release | FongMiBrowserPlayback cleanup | CookieManager仅返回媒体URL作用域Cookie，退出销毁及取消探测请求 | 浏览器专项；后续集成生命周期用例 |

## 保留的安全约束与差异

- 上游 ParseWebView 的 `SslErrorHandler.proceed()` 没有移入 HERRIN；TLS 错误必须失败，不绕过认证。
- HTTP 403 等保留实际状态分类；不从错误正文“提取媒体”，不引入无限重试。
- parser 自己提供的 HTTP 头只在合法请求范围使用。Cookie / Authorization 在跨 origin 跳转和结果地址变化时剥离；媒体 WebView Cookie 仅使用该媒体 URL 的 CookieManager 作用域。
- 解析输出只转换为 PlaybackMedia，无影片资料、TMDB、UI、导航、收藏写入。
- 本提交的旧 FongMiPlaybackRouter 保留非 Spider 调用 ABI；Spider worker 由 Agent 1 接入 `FongMiParsePlayback.resolve`，旧串行 Aggregate 执行必须在集成中删除。

## 必须诚实保留的缺口

目前 `ParseWebView` 是宿主浏览器接口适配，没有把上游 CloudflareDialog/挑战交互 UI、全局 RuleConfig 的站点广告规则/脚本配置系统搬进 HERRIN。无法处理需要可见交互授权的挑战时，应失败并提示授权要求；不得绕过挑战。浏览器支持范围不能宣称与上游全量站点规则等价。
已提供 Spider.manualVideoCheck/isVideoFormat 的可选回调，Agent 1 将其接到 worker；当前内容探测涵盖 MIME 与常见容器字节，但不宣称覆盖每个站点自定义格式。
这些缺口与不可获得的第三方片源授权/真机验证分开记录；本地 fixture 通过不是 TCL 第三方片源成功证明。

## 执行日志

编译及测试日志：`/workspace/m467-parsejob-build.log`。以下结果在实际执行后补充，不以代码存在代替通过。

首次完整 Gradle 实测在源码编译前失败：环境对 jitpack.io 返回 HTTP 403，既有依赖 `com.github.thegrizzlylabs:sardine-android:0.9` 无法解析，完整日志为 `/workspace/m467-parsejob-build.log`。总 Agent 已从官方源码构建本地验证依赖；使用 `/workspace/tools/local-sardine.init.gradle` 的重跑进行中，日志 `/workspace/m467-parsejob-build-local-sardine.log`。不修改生产依赖。独立 javac 已成功编译实际 Java port 与 fixture JAR 类；Kotlin/JUnit 执行结果待补，不宣称 Android 编译或浏览器用例通过。

追加验证：同一生产 Java port + 真实 fixture JAR 的独立 JUnit 已实际通过 10 项（`/workspace/m467-parsejob-standalone/test.log`；网络监听必须使用执行工具的已授权网络权限）。又增加 HTTP403 终止、聚合全部失败的两个回归，正在重跑。最终JSON/JAR/聚合返回的 `proxy://` 统一通过可选 `normalizeMedia`（worker 提供当前会话 rebaseUrl）转换，JAR needParse 同样转换后进入浏览器，避免解析成功后再被HTTP-only边界误拒绝。
