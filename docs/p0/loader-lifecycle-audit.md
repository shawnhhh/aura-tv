# Loader 生命周期：生产调用链核查与来源意图失效修复

2026-11-05；基线 `2cef56b629b86952ce7abddf2bcf5042ce5cc096`。
上游固定 `305dff233cde512e2c83f71529b7c3ecfd61fefc`。
对照 vendor 中 BaseLoader/JarLoader，以及该固定提交的
[SpiderLoader.java](https://github.com/FongMi/TV/blob/305dff233cde512e2c83f71529b7c3ecfd61fefc/app/src/main/java/com/fongmi/android/tv/api/loader/SpiderLoader.java)。
仅静态读取官方源码；未下载/执行外部插件，GPL-3.0 署名及原版文件不变。

## 十项实际生产核查

| 契约 | 当前生产证据与本轮边界 |
| --- | --- |
| 批准 JAR / 校验 | MainActivity 的 apply/候选检索使用 TvBoxSpiderPolicy.mayRun 校验来源根与 JAR spec；SpiderJarCache.obtain 再要求 HTTPS、MD5、大小上限、DEX ZIP 和只读文件。Service exported=false，进程隔离用于故障控制，不是插件权限沙箱。未更改这些策略。 |
| ClassLoader 创建/复用 | TvBoxSpiderWorker 以 SpiderRuntimeCompat.sessionKey(jar,site) 比较完整初始化输入，同 activeKey 复用，否则 close 后创建 DexClassLoader。上游按 JAR 缓存 loader、按 JAR+site 缓存 Spider；当前是单活跃站点模型。未盲目增加共享 loader 缓存。 |
| init/search/detail/player | worker.browse 执行 Init.init、构造 Spider、设置 siteKey/net/local、Spider.init，然后 home；search/details/play 使用 active，playerContent 后走生产 ParseJob。Service Mutex 串行处理这些操作。本轮不更改插件调用 ABI。 |
| 站点切换 | worker.search 检查 activeSiteKey；sessionKey 含站点/API/ext/header/JAR，切换会释放旧实例。本轮新增来源 epoch 使切换前排队的请求/旧发布失效。详情/播放 Film 自身没有完整来源归属字段，后续仍需独立回归核查，不能宣称此项全部闭合。 |
| 撤销/失效 | 原初次 BROWSE 在 sendRequest 返回后无条件写 lastBrowse，且请求可能在协程/Mutex 中排队。close 清除后晚到成功仍可重新写入授权描述。本轮修复此明确缺口；持久化的用户批准策略仍由原 mayRun 管理，epoch 不是新的执行授权。 |
| 资源释放 | bridge.close 解绑注册、拒绝 pending、取消 cleanup；worker.close 关闭 native proxy、清 CatVodProxy、dispose Spider/net/dialog/js、清 HTTP/session/loader 引用。本轮保证撤销立即生效及 main cleanup 顺序；已有真实原创 DEX/代理关闭/重开测试保留。已经在 JNI 执行且忽略取消的插件仍依赖原隔离进程 watchdog，不声称能强制停止 Java/JNI 指令。 |
| 初始化/关闭幂等性 | 同 activeKey 不重复初始化；active 清空后旧资源不应重复 dispose。本轮 Binder fixture 验证重复 close 不重复解绑，且关闭后只有新的显式 apply 可重新激活。worker 关闭与晚到 init 的发布仍无上游 checkOpen 等价机制，列为下一任务。 |
| JsonExt/JsonExtMix | 当前 FongMiParsePlayback 向 JarParser 传 activeDexLoader；Json 的 maps/url 和 Mix 的 maps/name/flag/url 与固定 JarLoader 对照一致。既有原创本地 JAR ABI 回归保留，不新增任何插件运行时或未批准 loader。 |
| 旧异步任务 | 原 newConnection 用 generation 排除过期 Binder 回调，但初次 BROWSE 发布和排队意图无同等保护。本轮在首次挂起前捕获 epoch、Mutex 后及绑定/发送前检查、结果发布再次检查，并保留 transport generation。 |
| 错误/脱敏 | 保留 SpiderFailureDetails 的固定分类与 URL/认证字段过滤；新拒绝错误是固定字面量，不含 JSON、JAR URL、Cookie、token 或原始异常。未回到旧 do= 白名单。 |

## 实际修复的单一子契约

**已经撤销或被新来源替代的旧来源意图不能执行排队加载，或用晚到结果恢复会话。**

新增 SpiderSourceSession 保存当前已应用描述和单调 epoch。beginBrowse/publish/revoke
按同一个 monitor 原子处理；新 apply 清旧描述，失败不复用旧描述。TvBoxSpiderBridge
的 browse/search/details/play 在首次可能挂起前捕获意图，Mutex 等待、服务绑定及发送时
都保留该意图；重试不会悄悄取得新 epoch。初次 BROWSE 的发布与 hydratedGeneration
在主线程一起检查，避免 close/invalidate 插入检查与写入之间。

close 在调用线程立即撤销来源，主线程解绑；主线程调用时直接清理，其他线程排队清理。
运输故障的 invalidate **不撤销用户已批准来源**，因此原有同源有限重建仍可使用原意图；
显式 close/新来源则会使旧操作失效。这是授权/Android IPC 生命周期适配，不是假装全量
移植了上游 Loader。上游对应原则是 checkOpen 在加载前后及 cacheLoader/cacheSpider
发布时再次检查 closed，避免关闭后的旧对象被重新使用。

## 回归及验证顺序

- 提取原来无条件发布的生产状态路径后，新增 5 项 JVM 回归。修复前 2 项失败：
  撤销后的晚到 publish、撤销前排队的 beginBrowse。修复后均通过。
- 回归还检查有效 apply、站点替换不接受旧 ticket、重复 revoke、失败新 apply 清旧描述。
  JVM 输入仅为仓库原创 fixture 的来源描述，不加载任何 JAR。
- 新增 SpiderLoaderLifecycleTest 两项设备回归，使用原创本地 Messenger fixture、真实
  TvBoxSpiderBridge 和可控 coroutine dispatcher。分别暂停于第一次主线程派发之前、
  成功 Binder 回复送达之后，然后 close，再恢复旧任务；要求失败、无授权描述、无旧
  服务重绑/重放。另检查新的显式站点应用成功和重复关闭的解绑次数。
- 设备 fixture 只返回自己生成的 JSON，不启动插件；既有 M467 原创 DEX JAR 全链路测试
  继续验证真实 ClassLoader/Init/代理/媒体出帧。新测试类加入 full-suite 必跑门禁。
- 按定向测试 → 静态/门禁检查器 → JVM 全量 → lint → Debug 与测试 APK 编译执行。
  设备测试失败清理也会取消并释放暂停 dispatcher，避免测试自身挂起。
  最新提交的远端 Debug/Emulator 结论必须按实际 CI 记录，不能继承基线成功。

## 尚未闭合的下一项

优先在原创 DEX fixture 构造可控延迟 init，对 TvBoxSpiderWorker 的晚到 active/loader/proxy
发布建立回归，再补与上游 checkOpen 等价的 worker 生命周期保护及临时资源回收。
之后继续 Film 来源归属、同 JAR 跨站点复用等必要契约；不据静态核查直接修改。
本轮只完成来源意图子契约，不宣称 Loader/阶段 B/整体 P0 完成；TCL 未实测。
