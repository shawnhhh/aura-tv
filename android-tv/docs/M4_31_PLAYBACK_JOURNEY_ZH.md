# HERRIN M4.31 — 无敏感信息的首帧诊断与播放阶段取证

## 适用场景
TCL T7L Ultra 真机历史反馈存在两类不同异常：
1. Spider 插件子进程退出，内容解析链路失败。
2. Spider 已返回剧集与播放地址，Media3 未出画面或报网络/服务器错误。

M4.30 已把 Spider 兼容、媒体错误分类、本地代理基础、HDR/杜比能力检测集成并通过远端 Debug/模拟器 CI。**后者不代表特定第三方来源在 TCL 真机成功起播**。

## M4.31 新增能力

1. `PlaybackJourneyTrace`：一个不会保存 URL、媒体 ID、请求头、Cookie、签名、异常文本的内存状态机。输入仅为播放器有限状态值、已选中音视频轨道标志、视频已输出帧计数、允许的失败类别、备用媒体顺序和单调计时。
2. 播放器打开时自动开始记录一条播放尝试，Media3 Player.Listener 在选中轨道、状态变化和播放错误时更新阶段；每 500ms 读取 Media3 decoder 的已输出视频帧计数确认首帧证据。
3. 备用线路切换会清除旧线路的首帧/轨道证据，保留上一次失败的**脱敏类别**；用户点击“重试播放”清理历史失败类别。整个过程使用当前已有的**有限**自动重试预算，不创造新地址、不绕开授权。
4. “查看实播状态”下增加 `player-journey-status`，区分：
   - 播放器准备、缓冲、已就绪；
   - 视频轨道、音频轨道是否实际被 Media3 选中；
   - READY 但尚未输出视频帧（不能提前认定“已播放成功”）；
   - READY + 视频轨选中达到 15 秒仍未出帧，建议检查解码和输出；
   - 持续缓冲超过 15 秒，建议检查网络或媒体清单/分段请求；
   - 音频轨正常而无视频轨的音频节目（不误判黑屏）；
   - Media3 失败的固定、无凭据类别。
5. 增加 JVM 测试覆盖首帧、持续缓冲、音频节目、备用线路切换、解码计数器重置、手动重试、时间回退及敏感数据不进入状态机。
6. 更新版本为 `0.4.31-alpha` / `versionCode 36`。

## 核心安全与验收边界
- 本模块**没有**读取或上报播放 URL、Cookie、请求头、私人授权数据，也不发起新网络探测；诊断只保存在当前播放器内存中，不会作为持久遥测收集。
- 视频帧计数表示 Media3 解码器报告的渲染输出帧，不证明 TCL 面板已进入指定 HDR 模式，也不证明 HDMI 或内置扬声器的实际音频格式。
- 遇到音频节目、静帧或其他特殊节目不可单凭超时告警自动判死；本阶段**只呈现告警，不自动终止或重置播放**。
- M4.30 本地代理仍保持独立且不自动接入 Media3，不开放跨域/DRM 绕过；M4.31 不修改 Spider 运行时或主分支。
- API 30 x86 TV 模拟器不能测试 ARM 真机 Spider/JNI、TCL Dolby Vision、HDR10+、自带扬声器输出。
- 单元和模拟器测试通过后，仍需要以用户**有权使用**的同一媒体来源确认真实 HTTP 状态、选中轨道、解码器计数与首帧。

## 自动验收
- GitHub Android Debug：`scripts/check_static.py` + `testDebugUnitTest` + `lintDebug` + `assembleDebug`。
- GitHub TV 模拟器：原有全量 instrumentation，保留视频、遥控器焦点、缓存、源与 IPTV 回归。
- 新增 Android TV instrumentation：用应用内授权的原创 MP4 测试素材取得真实 Media3 已渲染帧，展开「查看实播状态」并验证播放器诊断标签 `player-journey-status` 显示首帧，且不会包含 URL/host/token。
- 首帧信息默认只在用户主动展开“查看实播状态”时显示；不在后台进行采集或上传。

## M4.31.2 — CatVod 解析到实际首帧回归、解码器回退

- Debug-only `PlaybackTestActivity` 可接收测试自动生成的 `spiderJson`，使用生产相同的 `SpiderVodCodec.player()` 解析出 `PlaybackMedia`；只在 `android:exported=false` 的调试 Activity 中使用，不作为主界面可输入任意地址的功能。
- API 30 TV instrumentation 以本地 TLS HLS 清单及原创 TS 分段确认：来源给出的 `format=hls` 可以处理无扩展名清单，`Referer` / `User-Agent` 能传到清单与视频分段，Media3 输出视频帧；源服务器不满足请求头时测试服务器返回 403。
- Media3 引入 `DefaultRenderersFactory.setEnableDecoderFallback(true)`，首选系统 MediaCodec 初始化失败时允许选择其他兼容系统解码器；不引入 FFmpeg/VLC、不禁用系统 HDR、不声称 Dolby Vision/HDR10+ 已在 TCL 真机输出。
- CI 使用测试自行创建的 HTTPS 站点和原创播放文件，不依赖未经授权的第三方影片、Cookie、DRM 或运行时 Spider JAR。
