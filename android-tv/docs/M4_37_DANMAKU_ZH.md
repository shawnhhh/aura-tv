# M4.37 可选弹幕模块

本模块独立于 Media3 解码与播放请求。默认关闭；用户在当前播放控制区开启后，才读取当前插件明确返回的弹幕 sidecar。不查找默认第三方弹幕站，不发送播放 Cookie、Token、请求头，不下载插件未明确提供的搜索服务。

## 已明确支持的协议

对照 FongMi/TV `e3e81c27ec54d29689f4ef28cdeb9922f8fbc718` 的 `bean/Result.java`、`gson/DanmakuAdapter.java`、`bean/DanmakuData.java`，独立实现 wire format：

- `playerContent` JSON 的 `danmaku` 为 HTTP(S) URL 字符串（由用户主动启用才请求），或 `[{"name":"弹幕","url":"https://…/a.xml"}]`，或该数组的 JSON 字符串。
- sidecar XML：`<i><d p="秒,模式,字号,RGB,…">文字</d></i>`。模式 1 滚动、4 底部、5 顶部、6 反向；未知模式忽略。
- 没有声称支持任意插件私有弹幕 API、实时 `Spider.danmaku` 回调、未知 JSON 数据结构或第三方搜索接口。此版本仅支持上述明确 VOD sidecar。

没有复制 FongMi 源码；现有 CatVod SDK 的授权说明继续见 `CATVOD_GPL.md`。

## 架构与接线

`PlaybackMedia.danmakuSources` 在 Spider player JSON 解码后携带信息；IPC 使用 `DanmakuProtocol.encodeSources/decodeSources`，不改变视频 URL。

播放器中记住 `val danmaku = rememberDanmakuSettings()`。在 PlayerView 上、控制栏下叠加：

```kotlin
Media3DanmakuOverlay(player, mediaKey = media.uri,
    sources = media.danmakuSources, settings = danmaku.value,
    modifier = Modifier.fillMaxSize())
DanmakuControls(danmaku.value, { danmaku.value = it })
```

`mediaKey` 要能够区别影片、集数和线路（现有播放器以 media identity 重建时可用 URI）；不同集数共用同一 URL 的宿主应传影片/集数/线路组合键。源或 mediaKey 变动即清空旧弹幕；异步加载取消后不得返回旧会话数据。暂停、缓冲、seek 均直接读取 Media3 `currentPosition`，不以墙钟推算视频时间。每帧二分定位可见窗口，限制同时显示数量，避免全量扫描；异常被隔离为无弹幕，播放继续。

透明度、速度、字号持久化；启用状态不跨应用/播放器新会话持久化，避免首次自动访问未知服务。请求拒绝重定向，禁止 URL 用户名密码、file、live、localhost、内网直连 IP 等 scheme，超时5秒，最大2MiB，最多10000条；不输出 URL/查询串到日志或故障界面。开启后仅允许当时源的 HTTPS origin；换线/插件出现新 origin 时仍不自动请求，需用户重新开关启用。DTD/外部实体在解析前拒绝。

## 自动验证

- `DanmakuTest`：9项 JVM 测试覆盖三种 Result 数据形式、来源过滤与隐私、原创 XML 时间/模式/实体、坏行忽略、DTD拒绝、默认零网络/服务失败降级、暂停及前后seek/换集清空、速度/参数边界、数据及渲染数量上限。
- `DanmakuControlsTest`：Android instrumentation 使用 `sendKeyDownUpSync(KEYCODE_DPAD_CENTER)` 操作开关、透明度、速度、字号。
- `src/test/resources/danmaku/original.xml` 为 HERRIN 原创文字数据，不访问外部弹幕服务。

本分支静态检查成功。首次 Gradle 尝试在配置阶段因 `/home/agent/.android` 只读受阻；总集成使用可写 `ANDROID_USER_HOME` 后执行真实 JVM/编译/模拟器回归，以最终 CI 结果为准。本说明不把未执行的测试标成成功。

## M4.57 修复与诊断

- 按 FongMi 文档允许的 HTTP(S) sidecar 处理用户主动开启的弹幕（保持不跟随 HTTP 重定向，阻止凭据、localhost 与私有 IP 明文地址）；此举不代表支持来源未知的 `Spider.danmaku` 实时回调。
- 在全屏播放器的「更多 → 弹幕」处，来源没有返回弹幕字段时显示“当前线路没有提供弹幕数据”，避免将无法显示误判为渲染器故障。
- 切集或切线路时清空旧弹幕并根据当前视频重新加载来源，弹幕失败不能阻断 Media3 解码。
- 弹幕需要影视插件实际返回可访问的 XML 文件或 URL。第三方网盘密码与扫码授权是另一条独立链路。
