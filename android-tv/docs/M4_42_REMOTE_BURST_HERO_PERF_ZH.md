# HERRIN M4.42 — 连续遥控操作、首帧焦点与海报性能回归

## 背景和真实根因
继承 M4.41 Draft PR #48 的 cancel-old-focus-job 改动。在源码复查中发现：
1. 上下海报栏切换虽使用可取消焦点 Job，但外层仍各自启动 `browseScroll.scrollTo(...)`，会在焦点 Job 之外与下一次按键争用滚动位置。
2. 焦点 Job 以 `mutableStateOf<Job?>` 保存，每次 D-pad 更新 Job 都触发不必要的 Compose 快照变化。
3. 当前 Compose 版本的 `FocusRequester.requestFocus()` 返回 `Unit`，无法用 Boolean 检测成功；必须通过 Android TV UI instrumentation 对真实焦点持续断言，不能把“调用未抛错”当作最终成功。
4. 每次遥控移动都会立即更新全屏 Hero `Crossfade`，即使仅在中间经过某部影片，仍可能重复触发大位图解码、绘制和 360ms 过渡。

## 改动
- 首页上下栏：整页滚动、横栏对齐和目标焦点请求合并在**同一个可取消的协程任务**中；下一次按键会取消旧任务，没有等待过期动画。
- 设置页与海报栏：进行中的 Job 存在普通 `remember` 非快照容器里，不因为每次按键就重组整棵界面。
- 焦点请求保持最多三个显示帧的重试预算；不依赖不存在的 Boolean 返回值，以真实设备级 `assertIsFocused()` 断言检测丢焦点。
- 首页和搜索页文字与选中状态立即更新，背景 Hero 延迟 `180ms` 选择稳定后再跨入短 `180ms` 过渡；快速扫过的图片不再触发每张背景解码。详情页不引入该等待。
- 播放全屏和直播时不再在底下渲染大幅 Hero 背景，减轻播放器出帧期间无用 GPU 负担。
- Android TV 系统真实注入共 72 次方向键（32 次上下、40 次左右），每次校验最终焦点，记录输入至 Compose idle 的 P50/P95，并附 Window.FrameMetrics 合成帧 P95；无需第三方影视源。
- 新增 JVM 动画预算回归，限制 Hero 延迟+切换总预算小于 500ms，避免后续把“焦点立即响应”误改为慢动画。

## 验收边界
- Android Debug 全量 JVM/Lint/APK 和 Android TV 模拟器 instrumented tests 必须基于本 PR **最新 HEAD** 均成功才判定自动化回归完成。不能拿 #47/#48 的成功替代 #49。
- 模拟器运行结果不是 TCL T7L Ultra 真实按键到光子、4K/HDR、或 Infuse/VidHub 的同设备对比数据。
- 正式优化目标：焦点反馈同帧、可见卡片第一优先，P95 逐步收敛；CI 设备负载波动大，本轮 1000ms 上限仅保护不出现严重停顿，不是声称达到 60fps。
- 所有播放源/线路/授权/历史/TMDB/扫码/弹幕保持不变。保持独立 Draft、禁止合并主分支。

## 版本
`0.4.42-alpha` / versionCode 47。
