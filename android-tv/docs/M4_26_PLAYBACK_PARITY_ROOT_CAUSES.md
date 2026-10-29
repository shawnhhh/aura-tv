# HERRIN M4.26 — TVBox 播放兼容诊断与第一轮修复

范围：独立 Draft 分支，基于 PR #30 M4.25.1 head；不合并 PR #30 或仓库主线，不声明 TCL 真机已播放成功。

## 已有 TCL 证据与不能混淆的原因

- M4.17：饭太硬配置/站点列表成功，但 Spider 子进程退出；推测与第三方 ARM/JNI、前台 Activity 或 JS Host 差异有关，未获得崩溃 tombstone，不能定论。
- M4.19：用户真机已经加载 80 部真实影片和选集；播放提示网络或服务器不可用。因此 **不能把 M4.19 的出画面失败仅归因于 Spider 启动**。原始 Media3 errorCode/HTTP status 对照仍缺。
- GitHub x86 模拟器通过不代表第三方 ARM-only Spider、网络鉴权、代理链或 TCL Dolby/HDR 实际输出通过。

## 本 PR 已修改：来源声明的媒体格式一路传递

- FongMi/CatVod 官方 playerContent Result 支持 `format`，例如无扩展名但实际是 HLS 的 `application/vnd.apple.mpegurl`。
- 先前 HERRIN 只依据 URL 扩展名猜格式，并在特定错误后尝试 HLS/DASH；源返回的 `format` 丢失。
- 现在对 `application/vnd.apple.mpegurl`/`application/x-mpegURL`/HLS/M3U8 以及 `application/dash+xml`/DASH/MPD 进行严格白名单归一化；从 `SpiderVodCodec.player` 经 `SpiderIpc` 交给 `PlaybackMedia`，再由 Media3 `MediaItem.Builder.setMimeType` 使用。
- 同源流失败可沿已有逻辑按解析错误重试其他 manifest 类型；不开放任意 MIME、不添加外部播放源、不绕过站点权限。
- 补充 JVM 测试，检查无扩展名 HLS、DASH、Referer 原样通过 IPC，以及网页 `parse=1` 仍明确拒绝。

## 没有修复/仍待针对性实现的路径

1. `Spider` 运行环境与 FongMi 的 Activity / JsHost / Cookie/代理机制尚不一致；原生崩溃需要 TCL logcat/tombstone 确证。
2. `parse=1/jx=1` 的网页或仓库 `parses` 解析器当前仍不支持；需要独立合规解析适配层，不能直接将网页交给 Media3。
3. Spider `proxy/localProxy` 与本机代理服务器尚未实现；源返回依赖 TVBox 本地 HTTP 服务的 URL，HERRIN 无法直接照播。
4. 实际 HTTP 403/404、Token 过期、Cookie/Referer、HLS 分段响应、重定向等必须有脱敏诊断，不能盲目归因于视频解码。
5. FFmpeg/MPV 备用解码及 Dolby Vision/HDR10+/Dolby Audio 输出验证属于后续硬件兼容阶段；不能将识别标签当作电视实际输出。

## 近期验收步骤

- GitHub Debug：编译、单测、Lint、assembleDebug。
- Android TV 模拟器：全部 D-pad / Media3 回归测试；测试源必须是自有或明确授权。
- TCL T7L Ultra：使用同一合法来源，在 TVBox 与 HERRIN 对比插件 `play` 回传类型、HTTP 状态、分段请求和显示模式；只导出错误码、时间、阶段、域名哈希、header **名称**，不记录完整 URL、Cookie、Token。
- 真实播放画面、声音及 Dolby/HDR 面板输出均需后续实机确认。
