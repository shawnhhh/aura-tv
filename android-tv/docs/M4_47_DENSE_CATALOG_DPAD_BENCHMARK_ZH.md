# HERRIN M4.47 — 单仓 12 站点、60 部影片的遥控器实测焦点延迟

## 继承的真实指标

M4.43 的 API 30 x86 / tv_1080p SwiftShader 模拟器完整 CI 通过：
- 6 部原创模拟电影：左右方向键 40/40、焦点回调 P95 49.54ms；
- 上下方向键 32/32、焦点回调 P95 81.73ms；
- 不等待逐键 Compose idle 的 20 次左右反向连按最终焦点正确；
- 合成帧 P95 208.24ms，软件模拟器 79/79 个合成帧超过 16.67ms。

这些数据不能代表单仓大量影片的实际响应，也不能与 TCL 电视原生 GPU 性能画等号。M4.44～M4.46 已针对海报下载名额、图片取消、缓存锁碰撞进行代码优化；M4.45 另外增加固定重绘对照。

## 本轮直接在复杂片库场景测量

复用既有 `M438SingleWarehousePerformanceTest.singleWarehouseLoadsAllSitesAndDpadHasNoSevereStall`：
1. 通过本地 `MockWebServer` 导入包含 12 站点的原生 TVBox 配置、选中其中一个授权模拟 CMS 并加载 **60 部不同标题**。仍必须只访问被选中的那个 CMS；其他 11 个 Spider 站点不能自行执行或请求。
2. 聚焦第一张海报，启动 M4.43 已验证过的 `RemoteFocusLatencyProbe`；
3. 使用真实 OS `KEYCODE_DPAD_RIGHT` 连续移动 16 次，逐次等待 Compose 正常空闲，断言捕获 **16 次焦点回调且 16 个不同影片 ID**；
4. 测量原来的 D-pad→Compose idle P95（既有门槛 <600ms），并新增 **OS KeyEvent→目标海报焦点回调 P95**，以及系统帧 P95 与真实采样数；
5. 输出仅有数字的 `m447_dense_dpad_events` 遥控指标，既写入 `HerrinPerf` 日志，也写入 shell-owned evidence，防止完成 100 多项集成测试后 logcat 轮转丢掉；
6. `remote_perf_report.py`、`summary.json` 和中文验收报告新增「单仓 12 站点／60 部影片」专项表。新增 Python 单测，确保数值、缺失值及保密约束正确。

## 下一轮取舍依据

- 如果 60 片库焦点 P95 明显高于 6 片库（同规格 CI 模拟器），优先定位根组件全局重组、LazyRow 重新布局、缓存/解码线程抢占；
- 如果焦点 P95 差距很小，但合成帧一直极慢，先用 M4.45 相同软件模拟器固定重绘基线做对照，避免错误地优化方向键路由；
- 如果高密度场景超过原有 600ms 严重回归门槛，CI 必须失败，不能仅把阈值调大以掩盖问题。

所有新增指标需要当前 HEAD 的完整 Debug 与 TV Emulator 回归，保持 Draft，不合并主分支。模拟器数据不等于 TCL T7L Ultra 的 4K 真机帧率，也不是 Infuse、VidHub 的源代码比较。

版本 `0.4.47-alpha` / code 52。
