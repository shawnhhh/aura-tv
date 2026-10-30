# HERRIN M4.55 — 播放画面铺满 + 左上角文字彻底删除

## 用户反馈与具体要求

用户在 TCL T7L Ultra 实拍中指出：播放器画面未铺满电视，以及左上角出现「返回详情」「仅播放你有权使用的媒体 · 播放源按所选影片/线路确定」「查看实播状态」等覆盖文字。特别澄清：**这些左上角文字是直接不要，不能移到底部、不能变成按遥控器才显示、不能替换一批新按钮。**

## 播放页界面落实

- 默认 `PlayerView.resizeMode = RESIZE_MODE_ZOOM`（**中心裁切铺满**）：16:9 电视不会因为超宽电影源保留软件信箱黑边；宽高比不拉伸。镜头两端可能被裁去，属于用户可选行为。
- 底部原有播放控制条保留一个独立的「画面：铺满（裁切）/完整显示」切换按钮，后者使用 `RESIZE_MODE_FIT` 保留完整画面，按普通系统设置存储，下次进入播放器沿用。
- **删除普通播放器的左上角返回按钮、实播状态按钮和所有常驻提示**；不把「返回」「播放信息」按钮转移到屏幕底部。系统遥控器 BACK 返回功能保持可用。
- 在普通播放器中，所有 `player-verification-toggle` / `player-journey-status` / `player-verification-status` 诊断 UI 不出现。原始播放质量、HTTP、解码和旅程诊断仍可通过开发者工具检查；`PlaybackTestActivity`（`src/debug` 下的独立 Android 仪器测试容器）显式使用 `testDiagnostics = true`，以保持已有“首帧、播放恢复、诊断不会泄漏私有播放地址”等自动化测试。普通 `MainActivity` 从不传该参数。
- 播放失败、真正的媒体连接错误仍可显示中央错误信息与「重试」以避免遮蔽故障。
- 图片/媒体来源中*原视频自己带的*画面内水印不属于 HERRIN UI 文本，不能通过删除 Android 控件来合法去除，也没有修改原始影片数据。

## 本轮 CI 与测试

新增 `M455PlaybackViewportModeTest`（JVM）：默认铺满 / 保留比例 / 存储的显示偏好。扩展 `M453BufferingRemoteFocusInstrumentedTest`：实际消费者播放器（默认 `testDiagnostics = false`）中显式断言「返回」「播放信息」及旅程诊断标签都不存在，但画面比例切换仍可用；既有 `PlaybackDiagnosticsIntegrationTest`、`AuthorizedPlaybackJourneyTest`、`PlaybackButtonsTest` 通过独立 debug 测试容器继续执行诊断回归。

同分支还修复了 M4.54 原代码中三处明显的测试源码语法问题：`M420TvBoxCmsDetailTest` 误插入后导致后半文件重复与未闭合字符串、`M438SingleWarehousePerformanceTest` 缺少构造括号、`M454CmsOneTapRecoveryInstrumentedTest` 两处站点配置构造括号不配对。**是否通过仍须以本 M4.55 最新 HEAD 的 Android Debug 和 TV Emulator CI 为准。**

保持 Draft，不合并主分支，不改动用户已安装的 APK。
