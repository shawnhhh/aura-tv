# HERRIN M4.30 — 四路功能统一集成与验收（Draft）

## 目标与基线

- 基线：M4.27 独立 Draft PR #32 head `e1e8d6f44586cc2d8a7af5f1fc1b9777f0eaa0a2`。
- 只新建 M4.30 集成分支；**不合并**主分支，不更新/合并既有 #30–#36，不将 Draft 改为 Ready。
- 构建版本：`0.4.30-alpha`，`versionCode=35`。
- 保留 HERRIN 既有原生 Compose/Media3 UI、源身份/选集/进度和遥控器行为。

## 本次集成的来源（只提取各 PR 相对 M4.27 的实际文件变更）

| 任务 | 原始 Draft | 固定来源 head | 整合内容 |
| --- | --- | --- | --- |
| Spider | #35 | `2ca3e0e1aefb5c13713825810d76c982e179be0d` | SpiderRuntimeCompat、Spider Bridge/IPC、测试和中文报告 |
| 播放诊断 | #34 | `36fae85070fbe757e9b38e5cd0857f91b0429207` | Media3 错误分类、备用线路限制、MockWebServer/DASH 测试素材 |
| 本地代理 | #36 | `f5234d9ad6a6aab7027e2fe72c54d6ae0dfb20c1` | MediaLoopbackProxy/Policy、依赖、测试和中文报告 |
| Dolby/HDR | #33 | `0113574df72e20b66b4c2af8a3f95add18b23a33` | DeviceMediaCapabilities、MediaQuality、测试和中文报告 |

### 明确的冲突消解

1. 四个 PR 都曾修改 `.github/workflows/android-signed-release.yml`，但来源文件 blob SHA **完全一致**：`ae19720554b397a7d3f12eee20be6656872d42d7`。集成版只采纳一次；与基线相比修正 release keystore 环境变量注入。
2. `app/build.gradle.kts` 采用 #36 测试依赖增量，同时版本提升至 `0.4.30-alpha` / `versionCode=35`；其余非共享 Kotlin/测试/文档文件严格从对应 PR 的固定 head 引入。
3. 播放诊断测试所需的 DASH .m4s 二进制素材保留源 Git blob，不进行内容转码。
4. 所有原始 #33–#36 仍为 Draft，未对其分支作任何改动。

## 隔离策略和真实验收边界

- **MediaLoopbackProxy 暂未自动接入正式播放器**：它是限授权、loopback-only 的独立模块；其 SSRF、跨域、DNS 重绑定、过期 token、重定向与关闭资源行为需在完整集成和真实 TV 进一步审查，未完成前不默认开启。
- DeviceMediaCapabilities 是被动能力检测，不能以「可支持」「已选中 DV/Atmos 轨道」替代真实面板/扬声器输出确认。
- Spider JNI 的 ARM 插件与 TVBox 的 Activity/JS Host 行为仍可能不完全一致；API 30 x86 模拟器**无法证明**实机 ARM 加载与某个受保护站点均可播放。
- 不能以任意网络错误推断源 DRM、解析器、网络授权或解码器的单一根因。
- 不增加第三方影片、未授权播放 URL、Cookie、API Token 或加密破解；保护来源许可和 HTTP 请求头边界。

## 验收矩阵

| 检查 | 目标 | 现状 |
| --- | --- | --- |
| #33 独立 Debug/模拟器 | success | success / 76 tests |
| #34 独立 Debug/模拟器 | success | success / 78 tests |
| #35 独立 Debug/模拟器 | success | success / 77 tests |
| #36 独立 Debug/模拟器 | success | success / 76 tests |
| **本集成分支 Debug** | 静态检查、JVM、Lint、APK | **以集成 PR CI 结果为准** |
| **本集成分支 TV 模拟器** | 全量 instrumentation、不跳过、无失败 | **以集成 PR CI 结果为准** |
| TCL T7L Ultra 实机出画面 | 同一授权源成功解析，HTTP 成功，有首帧 | **尚未验收** |
| Dolby Vision / HDR10+ / Dolby Audio | TCL 面板/音频输出真实确认 | **尚未验收** |

## 集成测试的结论边界

只有 M4.30 的实际提交同时通过 Debug 与 Android TV Emulator CI 后，才能宣称“四个模块共存的自动化测试通过”。即便如此，仍不能宣称真实 TCL、受保护 CatVod Spider、Dolby Vision、HDR10+ 和电视自带扬声器杜比音效已经验收。

下一步可围绕统一的源 → Spider → 合法播放器媒体请求 → 解码首帧诊断链，新增使用自有或有权来源的端到端测试，并在完成 URL/会话安全审计后评估本地代理的可选接线。
