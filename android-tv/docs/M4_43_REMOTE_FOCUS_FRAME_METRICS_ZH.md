# HERRIN M4.43 — 实测焦点延迟、长帧占比和 Hero GPU 减负

## 基线
- 上游：M4.42 Draft #50，HEAD `64c565bd9bcff85b8825f9cf808d4524b768940c`。
- M4.42 的 Debug + TV Emulator 均通过：32 次上下和 40 次左右遥控器注入回归成功。
- M4.42 原有测试仅在 logcat 打印整数毫秒的 D-pad 到 Compose idle P95，
  **CI 验收报告并未列出真实数值**，也未记录实际焦点回调延迟。
  因此不能声称此前达到了 Infuse 或 VidHub 的延迟水平。

## 本轮开发
1. `RemoteFocusLatencyProbe`：仅当 Android 仪器测试主动开启时，从 Activity
   接到方向键 ACTION_DOWN 到 Compose `Poster.onFocusChanged` 计时。
   默认不开启，不写入历史、标题、播放源、URL 或令牌，不在日常使用中分配指标集合。
2. 72 次真实 Android TV KeyEvent 压力回归，逐键验证焦点目标：
   上下 32 次必须在 `blue/orbit` 间准确切换，左右 40 次必须在
   `tide/orbit` 间准确切换；**72/72** 是强制测试断言，而非虚构的 log 字段。
3. 同时测量焦点回调 P95（毫秒）、测试输入直到 Compose 空闲 P95（毫米秒）；
   `Window.FrameMetrics.TOTAL_DURATION` 计算帧 P95、60Hz 超 16.67ms
   长帧数量和占比、超过 33.33ms 严重长帧数量。长帧比例先做基线观察，
   不在缺少真机稳定基线时设定误导性的 60fps 强制断言。
4. CI 每组测量输出仅包含数字，logcat 和独立 shell 所有者指标文件双通道保留；
   Android Emulator 的 `summary.json`、`REPORT.md` 和中文 `验收报告.md`
   自动读取并展示真实 P95 和长帧比率。缺失样本显示「未采集」，不自动填零。
5. 改善 GPU 负担：取消竖版电影海报作为 Hero 背景时 32dp 全屏
   RenderEffect 模糊的离屏处理，保留低透明度的图片氛围层、
   显式暗色渐变及清晰比例正确的主海报。
6. `android-tv/scripts/test_remote_perf_report.py` 测试指标解析、缺失值和
   影视资源信息不泄露，模拟器 CI 在编译前先执行 Python 单测。

## 验收准则
- 焦点正确率：72/72，没有静默丢失焦点。
- **测试用模拟器预算**：焦点回调 P95 <750ms，输入至 Compose idle P95 <1000ms，
  必须存在 FrameMetrics 合成帧样本。这是宽松的严重回归门槛，
  **不是** 60fps 达标指标。
- 长帧比例：显示原始样本总数及 >16.67ms 占比；在首次可靠基线采集后，再按
  相同模拟器规格、相同场景对比。不能把不同机器的数值拼在同一条趋势线上。
- 同一 HEAD 必须同时通过 Android Debug + Android TV Emulator 的所有 CI。
- 每轮只创建 Draft PR；不碰主分支，不合并。
- 未在 TCL T7L Ultra 上实测屏幕扫描延迟、4K GPU 负担、实际遥控器硬件时延，
  也未与 Infuse、VidHub 进行同设备仪表对照。焦点回调 P95
  **不等于** 按键到用户看见高亮的光子延迟。

## 输出
- `android-tv/build/emulator-evidence/summary.json`（`remote_performance` 字段）
- `android-tv/build/emulator-evidence/REPORT.md`
- `android-tv/build/acceptance/验收报告.md`
- `android-tv/build/emulator-evidence/test-evidence/remote-performance.log`（若 ADB 允许）
- GitHub Actions Emulator/Debug 独立构建日志与 Artifacts

版本：`0.4.43-alpha`，code `48`。
