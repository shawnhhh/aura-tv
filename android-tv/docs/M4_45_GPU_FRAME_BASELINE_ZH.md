# HERRIN M4.45 — 相同 TV 模拟器中的“固定画面重绘 vs 遥控导航”对照

## 为什么要增加对照基线

M4.43 HEAD `1cbf90cc6eae36d7ead7eb426add113322bae74a` 的 Android Debug 与 TV Emulator CI 全部通过，Android TV API 30 x86（tv_1080p、SwiftShader）实测：
- 左右 40 次按键：目标焦点 40/40，KeyEvent 到 Compose 焦点回调 P95 = **49.54ms**。
- 上下 32 次按键：目标焦点 32/32，焦点回调 P95 = **81.73ms**。
- 20 次不逐键等待空闲的快速左右连按：最终焦点正确，整组耗时 **691ms**。
- 水平导航合成帧 P95 = **208.24ms**，FrameMetrics 样本 79 帧，长于 16.67ms 的帧 79/79（100%）。
- **注意：这不是 TCL 真机的 100% 长帧率，不能据此宣称产品 5fps**。GitHub 软件模拟器的图形驱动、虚拟化以及调试 instrumentation 会显著影响合成帧耗时。

因此，直接拿绝对软件模拟器 P95 评判 Herrin 相比 Infuse/VidHub 的丝滑程度不成立，必须先知道这个图形环境在不切换焦点时绘制同一主页的基础成本。

## 本轮真实增量

1. 新增 `M445FrameBaselineTest`：在 **同一个模拟器、同一个首页、同一个 FrameMetrics 监听器** 下先强制固定画面重绘（24 次），随后进行 24 次真实遥控左右按键+逐次目标焦点断言。
2. 分别采集固定画面重绘与导航阶段的 FrameMetrics，总数、P95 和导航/重绘比值。只测得某一组则标记「未采集」，不捏造另一组。
3. 指标写入无用户名/播放源/URL/令牌的 `HerrinPerf` 日志，以及 shell evidence 指标文本；`remote_perf_report.py` 将其写入当前 Emulator JSON、Markdown 和中文验收报告。
4. 新增 Python 测试，验证数字解析、缺失样本保持「未采集」、不会把其他标签当作应用数据。

## 分析方法和下一步

- 两者 P95 **都很高且比值接近 1**：先认定软件模拟器绘制瓶颈占比较大，降低全屏合成层数、背景过渡层数、测试环境复杂度；不能据此指责 D-pad 业务逻辑。
- 只在导航时 P95 明显上升：重点检查焦点触发的整个 `Herrin()` 重组、图片状态刷新、焦点动画与布局失效；再写对应单元/设备测试。
- 比值只是探索性的环境对照，不是毫秒级真机基准，更不是实际按键到光子的测量；框架 `decorView.invalidate()` 是强制重绘近似基线，并不能和 Compose 真正状态变更严格等同。
- 遥控器按键切换本身仍要求无焦点丢失；保留原 92 次方向键回归及播放器、字幕、网络、TMDB 等全量用例。
- Android Debug + Android TV Emulator 均需在本 PR 最新 HEAD 同时通过。保持 Draft，不合并。

版本 `0.4.45-alpha` / versionCode 50。
