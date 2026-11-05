# TCL 64：SOURCE_PROXY_IO_FAILURE 专项

基线 819f77ce37d8236cde82556e649e7bf8aef5d98f，固定 FongMi/TV
305dff233cde512e2c83f71529b7c3ecfd61fefc，GPL-3.0 / 原作者 FongMi。

## 证据与定位

用户真机屏幕只有 SOURCE_PROXY_IO_FAILURE / SPIDER PLAYER_CONTENT_REJECTED；
没有底层异常类别，不能据此断言 TCL 是连接拒绝、网络策略或主线程错误中的哪一种。
宿主产生此码的位置是 bridge 收到 Ready、解码媒体结果之后的 /health 探测，
已在 Spider.playerContent、代理地址转换和 ParseJob 返回之后，Media3 初始化之前。
PLAYER_CONTENT_REJECTED 是 UI 对全部 Unavailable 的笼统事件，并非证明插件没有返回内容。

生产 LaunchedEffect 在 Main 调用 bridge.play。Binder 挂起后恢复原调用线程，
原同步 HttpURLConnection 探测仍在 Main；Android NetworkOnMainThreadException
落入 catch(Exception) → IO_FAILURE，继而触发一次无效重建，第二次仍在 Main。
此前端到端测试在 instrumentation runBlocking 线程调用，因此漏掉实际入口的线程语义。

修复将两次探测切到 Dispatchers.IO，返回后检查 source epoch 和 transport generation；
重建前在 Main 再校验同一来源/generation。取消和主动撤销不会发布旧结果或重建新来源。
IO_MAIN_THREAD / IO_POLICY / IO_TLS / IO_STREAM / IO_UNKNOWN 不当成代理死亡来重建；
IO_CONNECT / IO_SOCKET / CONNECT_REFUSED / TIMEOUT / HTTP_5XX 保留有界运输重建。
未知错误不再只有 IO_FAILURE；只输出有限代码，不输出异常消息或 URL。

## 固定上游与生产差异

| 链路 | FongMi / Herrin 实际行为及本次处理 |
|---|---|
| SpiderLoader / JarLoader | 上游 checkOpen/cacheSpider/按 JAR 缓存；宿主有批准凭据、epoch 和隔离进程。原已验收生命周期不重复实现。 |
| Spider.playerContent | 同步接口；宿主在 worker 的 runInterruptible(IO) 执行，已加 STARTED/RECEIVED 事件；未改插件参数/执行权限。 |
| Nano / Proxy | 上游 HTTP 工作线程派发，params+headers→BaseLoader.proxy，返回真实状态/MIME/InputStream，排除重复 Content-Length/Transfer-Encoding。宿主保留这些转发语义，新增受 session/site 校验的随机 loopback listener 与 /health。 |
| 宿主健康门禁 | 固定上游上述生产链没有这个同步 /health 门禁；缺陷在 client 额外探测的线程，而不是 do 操作白名单。未凭猜测替换已验证的代理服务器。 |
| ParseJob / ParseWebView | 上游 executor 处理解析、UI 承载 WebView/回调；宿主在隔离 worker 使用现有适配。未重写取消/超时及 Header 来源边界。 |
| PlayerManager / MediaItemFactory | Media3 真正开流前被旧健康门禁拒绝，未到解码阶段。本次保留唯一生产播放器与 Headers/媒体对象管理。 |

## 回归范围

- A：真实 Android Main + StrictMode 对正常 :spider listener 做同步探测，分类为
  IO_MAIN_THREAD，随后在 instrumentation 线程同一端点 READY；证明 I/O 异常不等于 listener 已死。
- B/C/D/F：现有 NativeFongMiRouteTest 覆盖 proxy://、旧 127.0.0.1 端口重绑定、
  失效/跨站 token 拒绝、同 JAR 已签发静态来源继承和普通外部 URL 不变。
- E：原创已批准 DEX→Binder→从 Main 发起 play→/health→Media3 真视频与音频缓冲；
  杀掉本应用 :spider，等待真实断线事件，同一已批准来源重新装载并取得新 capability，再次解码。
- G：新增 HTTP 回归，正常 /health READY 但插件媒体返回无效响应时 HTTP 502；不以健康成功冒充媒体可播放。
- H：保留原真实 MP4/HLS/TS、Range/请求 Header、代理鉴权/跨站拒绝及遥控器全量门禁。
- JVM 对 CONNECT/SOCKET/POLICY/TLS/STREAM/UNKNOWN/timeout 编码、嵌套异常、UI 脱敏及禁止策略失败自动换源回归。

本机无 Emulator/KVM，设备证据必须以本提交最终 SHA 的串行 CI 为准；不将编译当设备通过。
原始插件及 TCL 私有网络/ROM 不在此环境，实际来源的出帧/声音必须装 65 复测。

安全日志只取 HerrinPlaybackDiag：

PLAYER_CONTENT_STARTED → PLAYER_CONTENT_RECEIVED EMPTY=0 → PLAYER_CONTENT_RETURNED
→ HEALTH_STARTED → HEALTH_READY（worker HTTP_RESPONSE STATUS=204）→ MEDIA_ROUTE_READY
→ PLAYBACK_INITIALIZING → FIRST_FRAME / TRACKS_CHANGED AUDIO=1。

若仍失败，提供 HEALTH_IO_*、HTTP_RESPONSE STATUS、PLAYBACK_ERROR CODE 的固定代码；
不要发送 Token、Cookie、Authorization、完整 URL 或全量第三方日志。
