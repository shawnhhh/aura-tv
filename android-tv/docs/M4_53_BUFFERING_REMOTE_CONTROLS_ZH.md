# HERRIN M4.53 — 播放器缓冲阶段遥控器立即可操作

## 自主排查发现的交互问题

在 M4.52 及此前的 `AuthorizedPlaybackPanel`：
- 底部播放控制条只有满足 `controlsVisible && hasDecodedVideo` 才渲染；
- 用户在播放器刚打开、媒体正在连接或缓冲时按 DOWN/CENTER，原代码进入一个 50ms 轮询 `videoDecoderCounters.renderedOutputBufferCount > 0` 的循环，**迟迟不请求控件焦点**；
- 如果网络较慢、解码器还没有输出任何帧，就会出现“按了下键但没有任何可选中的控件”，与电视流媒体 App 的交互预期不符；
- 如果播放器最终出错，原焦点任务还没有把 Retry 变成确定的 DOWN 焦点。

## M4.53 实际修复

1. 新增纯逻辑 `PlayerRemoteFocusPolicy`：将影片播放时的焦点目标明确分为 `TRANSPORT`（正常控制条）、`RETRY`（播放故障）、`NONE`（直播保持自己的频道抽屉行为或未请求）。
2. **底部控制条从播放会话启动就可以渲染和聚焦**，不再等待 Media3 解码第一帧；播放器初始缓冲期间遥控器向下仍可选中播放/暂停、跳转等按钮。
3. 原 `focusControlsEpoch` 任务去掉无上限的“等视频帧”轮询；按 DOWN/CENTER 后直接请求已挂载的 Compose 控件。若故障则请求带 `retryControl` 的可见重试按钮，而不是继续等一帧永远不会出现的视频。
4. 已解码后的画质、字幕、音轨、分集选择等内容与功能保持不变；直播画面仍以一像素 Player Focus Anchor 控制频道抽屉，不显示电影进度条。
5. 不修改 DRM、媒体地址、网络权限、TVBox 插件执行或普通用户不应看到的解析信息。

## 自动化测试

新增 4 组 JVM 单元测试：
- 缓冲状态（不依赖任何已解码帧）仍显示并可聚焦播放控制条；
- 发生播放错误时 DOWN 对准 Retry，不显示失效的播放控制条；
- 自动隐藏控制条时不把焦点送往不可见按钮；
- 直播频道仍保持独立的方向键/频道抽屉逻辑。

新增 **Android TV 真实 OS KeyEvent + 本地 MockWebServer** 的 `M453BufferingRemoteFocusInstrumentedTest`：HTTP 连接建立后服务端不发送任何视频数据，播放器解码帧数为零的缓冲场景下，实际注入 DPAD_DOWN，必须立即把焦点交给 `transport-toggle`；旧版“等待首帧”逻辑会在这项测试中超时。只使用本地测试服务器，无真实影视源。

沿用 Android TV 100+ 全量模拟器回归，包括 72/72 海报焦点、20 次遥控连续键、60 影片密集列表、播放和字幕验证、设置 7 键突发等。最新 HEAD 的 Android Debug、JVM/Lint 和 Android TV Emulator 均成功后才算自动化验收；TCL T7L Ultra 真机仍需测试厂商遥控器和实际视频硬解码。

版本 `0.4.53-alpha` / code 58。接续 M4.52 Draft #60；**新 PR 依旧 Draft，不合并 main/master**。
