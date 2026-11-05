# 固定上游 ParseJob 方法级差异核对

日期：2026-11-05。固定参考：FongMi/TV
`305dff233cde512e2c83f71529b7c3ecfd61fefc`。原版路径：
`android-tv/vendor/fongmi-tv/app/src/main/java/com/fongmi/android/tv/player/parse/ParseJob.java`。
生产适配：`android-tv/app/src/main/java/tv/herrin/app/fongmi/parse/ParseJob.java`。
原版文件及 GPL-3.0 未修改；生产文件的较早来源记录保留，不改版权头冒充重新移植。

## 方法与实际调用契约

| 方法/方法组 | 固定上游行为与当前差异 |
| --- | --- |
| 构造、`create` | 原版全局 App/VodConfig 服务改为注入 Host；同样创建单 worker 和聚合池。WebView 集合改为并发 AutoCloseable 队列，供跨进程/协程宿主释放。 |
| `start` | 先 setParse 后 execute 一致；保留已验证的同步启动异常 stop，不回退到原版泄漏行为。 |
| `setParse` | useParse → 默认解析器；json: → type 1；parse:name → 命名解析器；空项 → playUrl/type 0。站点 click 优先、parser/result header 优先级一致。发现默认 Config 列表缺 Super，见下节修复。 |
| `resolveHeaders` | 配置 header 非空则完全覆盖 Result.header，否则复制 Result.header；两版均返回副本。 |
| `execute` | 原版依赖 FutureTask.cancel 的结果；当前保留整个异步 ParseJob 的 deadline，不只覆盖 worker。scheduler/期限由 Host 提供，取消/超时修复不重做。 |
| `parseResult` | 相同后台分派/失败回调；增加 Host.onFailure 的脱敏错误分类入口。 |
| `doInBackground` | 类型 0 WebView、1 JSON、2 Json 扩展、3 Mix 扩展、4 Super，一一对应，无新类型或旧代理补丁。 |
| `jsonParse(Parse, url, fatal)` | OkHttp 全局改为 Host.newCall；仍注册 Call、检查 done、关闭 Response、finally 移除 Call，IOException 的取消语义保留。 |
| `readJsonResult` | 顶层 url/data.url 及响应 UA/Referer/Cookie 替换规则一致；当前额外拒绝 HTTP 非成功状态和空 body。 |
| `jsonExtend` | 仅 type 1 构建有序 name → extUrl，注入已授权 loader 的 Json 扩展；结果经过同一 checkResult。 |
| `jsonMix` | 全解析器构建有序 name → mixMap；调用顺序 flag/key/name/maps/url 与固定上游对应。 |
| `superParse` | 按 type+flag 取得 JSON/WebView 候选，JSON 竞速、WebView 异步、首成功一次回调、失败 latch 收敛一致。默认不自动执行 type 2/3 JAR 扩展。 |
| `jsonParse(latch,...)` | 单个聚合候选失败不致命、finally countDown；去除原版直接 printStackTrace，保留安全 Host.onFailure。 |
| 两个 `checkResult` | 字符串结果空值/fatal 行为一致；对象结果覆盖 parseHeaders，needParse 时继续浏览器。UrlUtil.convert 改为会话授权 Host.convertUrl，不能接回旧 SpiderProxyPlayback。 |
| 三个 `startWeb` 重载 | parser/site click 和 header 选择、player/?url= 的 detect 特例一致。Android WebView 全局改为宿主 coroutine；保留晚到 handle、同步启动失败、UI 派发拒绝清理。 |
| `getHeaders` | 当前 parser 取已合成 parseHeaders，其余 parser 复制自身 header；与上游相同。 |
| `getHeader`、`fixHeader` | 与固定 UrlUtil.fixHeader 对照：ua/User-Agent → User-Agent，Referer/Cookie 大小写归一；没有扩展其他敏感头转发规则。 |
| `onParseSuccess`、`onParseError`、`complete` | 保留 done CAS 的单次结果；App.post 改 Host.post，拒绝派发时立即清理并保留异常。此为已验证宿主修复，本轮不重新实现。 |
| `deliverResult` | 调用结果回调，finally stop，一致。 |
| `stop` | 清空 callback、取消 deadline/Calls、关闭两工作池、释放全部浏览器句柄；使用线程安全队列并保留晚到句柄回收。 |

这是逐方法核对及已知差异记录，不是对所有外部配置/运行时的完整兼容认证。

## 本轮实际闭合：Config → ParseJob 默认/命名/flag 契约

固定 `VodConfig.setParses` 在非空列表前插入 `Parse.god()`；没有保存的选择时选第一项。
`Parse.god()` 的类型为 4，默认资源 `app/src/main/res/values/strings.xml` 的 `parse_god`
值为 **Super**。原 Herrin Host 则直接选择首个源配置项，因此第一个 JSON 接口 500 时，
后续正常接口可能根本不被调用。

新增生产 `ParseCatalog` 复用上游的上述规则及 `getParse(name)`、`getParses(type,flag)`：
非空列表插入 Super、空列表保持空、按名称找不到返回空解析器、flag 无匹配时回退到同类型
全部候选。FongMiParsePlayback 的 Host 直接委托此契约，不在测试中复制选择逻辑。
Super 名称取固定上游默认资源，仅用于播放宿主，不增加或修改解析器选择 UI。
源配置仍由现有数量上限、URL/授权检查处理；未增加外部 JAR、JS/Python/node 执行范围。

回归贯穿实际 TvBoxConfigParser → Parse 模型转换 → ParseCatalog → ParseJob → 两个
MockWebServer HTTP 解析接口：第一个 500，第二个返回媒体地址；检查第二项 User-Agent。
额外 type 2 项的 JSON 扩展回调一旦被执行就令测试失败。另覆盖空配置、命名解析、flag
匹配/回退。保留全部旧取消、deadline、清理测试及旧测试断言。
红灯记录：3 项中 2 项失败，分别为 type 4 默认项缺失和可用第二接口未返回。

## Loader、Spider 和 Proxy 核对边界

- 固定 BaseLoader.jsonExt/jsonExtMix 委托 JarLoader；当前委托的是本会话已批准 ClassLoader。
  Json/Mix 类名、LinkedHashMap 参数类型和 Mix 的 maps/name/flag/url 顺序已对照一致；
  已有真实原创 fixture JAR 回归继续保留。当前有类名限制，禁止任意新运行时加载。
- 完整 BaseConfig/VodConfig 的 DB 选择持久化、全部站点/运行时加载生命周期尚未移植；
  本次只闭合 stateless 播放宿主默认/命名/flag 子集，不宣称 Config/Loader 整体完成。
- playerContent 仍在隔离 worker 内生成后进入同一 ParseJob，NativeFongMiProxy 的站点/
  session、HTTP 响应/Range/HLS 路径未修改。已有 179 项 TV 模拟器门禁验证了基线实际
  MP4、HLS/TS 出帧和遥控器 fixture，不等同于第三方真实源/TCL 实测。
- PlayerManager/MediaItemFactory 定制 Media3 依赖缺口仍在；不提前进入 C 或删除代码。
