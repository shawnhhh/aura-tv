# HERRIN M4.52 — 播放中遥控器长按左右连续快进快退

## 自动代码审查发现的交互问题

在 M4.51 `AuthorizedPlaybackPanel.kt` 的播放器 `onPreviewKeyEvent` 中，只有 `repeatCount == 0` 的第一次 D-pad Left/Right KeyDown 才调用一次 `PlaybackTransport.seekBy(..., ±10_000L)`。标准 Android TV 遥控器的长按会产生 `repeatCount > 0` 的后续 DOWN 事件，但旧实现把这些事件全部标记处理、**实际没有任何后续跳转**。因此用户按住快进/后退，播放器通常只能移动 10 秒，和 Netflix、Infuse 等连续遥控操作预期不符。

简单取消 `repeatCount == 0` 过滤也不好：Android 系统长按可每秒发送多个 repeat，若每个都触发独立 Media3 SeekTo，会不断刷新视频解码和缓冲，造成播放器黑屏/延迟，实际体验更差。

## 实际代码修复

- 新增无协程、无全局共享状态的纯 Kotlin `RemoteHoldSeek`，绑定当前唯一媒体 `remember(media)`。
- 第一次 KeyDown 仍立即±10 秒；长按 KeyDown 后续每次累积±5 秒，但最多 **240ms** 才将这批意图实际提交一次 Media3 跳转，单批累积上限 30 秒；KeyUp 把未提交的步长补齐。连续反向和新媒体都会清理旧状态。
- `KEYCODE_DPAD_LEFT/RIGHT`（只在影片 Root 获得焦点时）与独立媒体快进/快退键共用节流累积器。若焦点正在控制条，方向键继续交给 Compose/控件导航，不会“左右选按钮却误快进”。
- 实际 `PlaybackTransport.seekBy` 返回 Media3 已接收、按片长边界限制的目标位置；立即更新进度条可见时间，不必等原先定时刷新到来才显示。
- 根容器原先每个长按 repeat 都更新 `controlsInteractionEpoch`，触发主控制层重组；本轮只在第一次触发和实际提交 seek 时刷新，降低不必要的界面重组。
- 现有播放器错误界面、清晰度、字幕、音轨、选集、直播频道、播放历史和授权逻辑保持不变。

## 单测与回归

新增 `M452RemoteHoldSeekTest` 七项 JVM 单测：
1. 单次短按左右均立即准确跳 10 秒；
2. 按住时多次 repeat 合并成一批、而不是逐键 SeekTo；
3. 快速松开时把未提交部分冲刷到播放时间轴；
4. 视频渲染线程暂时卡顿时，单批步长最多 30 秒；
5. 错方向 KEY_UP 不能清除当前有效长按；
6. 反向按键或更换片源时清除之前的意图；
7. 事件时间回拨不能造成负时间计算。

当前 HEAD 的 Android Debug（编译、JVM、Lint、打包）与 Android TV Emulator 的原有全量 100+ 测试均成功，才能确认未发生回归。完整的 Android TV 长按按键行为仍受不同厂商遥控器的 repeat 速率影响，模拟器只验证代码与其自带的 Android 输入栈；TCL T7L Ultra 的实际遥控器体验需真机复核。

## 边界和原则

- 不是无限速度的进度条拖拽，而是有限、可控的连续 5 秒步进。无需新权限、额外解码线程、下载策略或不合法媒体来源。
- 不从任何测试/网络读取用户真实收藏、播放源、token。
- 基于 M4.51 Draft #59 创建独立 Draft；不合并主分支。
- 版本 0.4.52-alpha，versionCode 57。
