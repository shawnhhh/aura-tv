# 饭太硬真实 Spider 专项：ARM 转译可运行，两个节点播放未通过

本次授权测试片目为用户提供的《小城良方》（2026），允许测试多集。
真实配置地址：http://www.饭太硬.net/tv 。使用正式 `TvBoxConfigRepository.loadUserWarehouse`，不接受缓存回退作为本次真实请求成功。节点按实时配置名称识别，没有默认 YGP 回退。

真实专项源码：`8fc9a652131c86f4c147ee75841fd07ae8e14d55`。
[最终专项运行](https://github.com/shawnhhh/aura-tv/actions/runs/42778911339)。任务绿色和 `OK (3 tests)` 表示 JNI/两个节点的证据采集完成，**不表示两个节点播放通过**；真实结论必须读取各 JSON 的 `verdict`。

## 两节点独立结论

| 节点 | 实时 siteKey / API | 最终结论 | 实际最远阶段 |
| --- | --- | --- | --- |
| 玩偶哥哥 | `玩偶` / `csp_WoGGGuard` | **FAIL_PLAYBACK** | ARM64、ARMv7 初始化成功；正式搜索返回空内容并触发 JSON 解析错误。补测正常首页加载，仍无影片/分类，随后站点未就绪。 |
| 奶酪 | `奶酪` / `csp_T4Guard` | **FAIL_PLAYBACK** | 初始化、搜索、2026 年份匹配、详情、29 集选集及三次真实播放解析成功。有效媒体验证未通过；首集本机代理 HTTP 502，错误码 `HLS_MANIFEST_REJECTED`。重播第1集和切第2集时新的本机 URI 未通过当前归属校验。 |

两个节点均没有有效媒体出帧、连续播放、暂停恢复或 Seek 的成功证据。没有触发可验证的真实登录二维码，不能标记 `AUTH_FLOW_PASS_PLAYBACK_PENDING`，也不能据此判断“不需要登录”。`UNAUTHORIZED_LOCAL` 是本机代理归属验证失败，不等于网盘私人登录状态。

## 架构实测

| Android 11 环境 | 实际包/进程 | 原创 ARM JNI | 真实插件初始化 |
| --- | --- | --- | --- |
| 官方 TV x86 | `x86` / `i686` | ARM32 加载实际报 EM_ARM/EM_386 不匹配；无 native bridge | 两节点 ENVIRONMENT_BLOCKED，均实际尝试过 |
| Google APIs x86_64 普通包 | `x86_64` | 系统有 `libndk_translation.so`，但默认 x86 进程直接加载 ARM64 实际失败 | 两节点 ENVIRONMENT_BLOCKED，均实际尝试过 |
| Google APIs x86_64、ARM64 测试包 | `primaryCpuAbi=arm64-v8a`、`os.arch=aarch64` | JNI 实际执行并返回 183，24ms | 两节点均成功 |
| Google APIs x86_64、ARMv7 测试包 | `primaryCpuAbi=armeabi-v7a`、`os.arch=armv7l` | JNI 实际执行并返回 40，6ms | 两节点均成功 |

Google 镜像的 `Build.SUPPORTED_ABIS` 为 `[x86_64, x86, arm64-v8a, armeabi-v7a, armeabi]`。因此没有仅凭 x86 名称跳过。
测试包通过正常的 Gradle ABI filter 限制所有原生依赖，再用 Android 标准 `adb install --abi` / instrumentation ABI 选择，并核对包管理器实际 ABI；没有修改系统 ABI 属性、插件二进制、反篡改或授权逻辑。
早期仅加入 ARM canary 的包仍被 Compose 原生依赖选为 x86_64，这一轮没有计作 ARM 转译成功；最终结果来自修正后的真实 ARM 包选择。

完整 ARM64 系统镜像也安装并尝试启动过。当前 x86_64 主机的官方 QEMU2 明确报：ARM64 AVD 不支持该主机架构。这个限制与已经运行成功的进程内 native bridge 是两回事。
当前会话没有云端真实 Android 设备服务/凭据，未调度或声称验证真机。普通、ARM64、ARMv7 的成对 APK 已归档，可供配置好的 ARM 真机服务后续使用。

参考：[Google 官方 ARM 转译说明](https://android-developers.googleblog.com/2020/03/run-arm-apps-on-android-emulator.html)、[Android Emulator 加速与主机架构](https://developer.android.com/studio/run/emulator-acceleration)、[云端设备 instrumentation 支持](https://firebase.google.com/docs/test-lab/android/instrumentation-test)。

## 实际阶段耗时（ms）

Instrumentation 实测包含每100ms一次的完成检查及必要的UI同步，不能当作 TCL 性能基准。

| 阶段 | 玩偶 ARM64 | 玩偶 ARMv7 | 奶酪 ARM64 | 奶酪 ARMv7 |
| --- | ---: | ---: | ---: | ---: |
| 实时配置 | 1204 | 1314 | 1102 | 702 |
| Spider 初始化 | 8013 | 4609 | 1704 | 601 |
| 搜索 | 101（错误） | 101（错误） | 601 | 401 |
| 片名年份匹配 | 未到达 | 未到达 | 102 | 100 |
| 详情 | 未到达 | 未到达 | 301 | 203 |
| 选集 | 未到达 | 未到达 | 101 | 101 |
| 第1集首次解析 | 未到达 | 未到达 | 401 | 403 |
| 默认媒体探测 | 未到达 | 未到达 | 1602（超时） | 1604（超时） |
| 扩展时限诊断 | 未到达 | 未到达 | 2405（本机HTTP502） | 2205（本机HTTP502） |
| 第1集重新解析 | 未到达 | 未到达 | 701 | 301 |
| 第2集解析 | 未到达 | 未到达 | 3709 | 1906 |
| 节点总时长 | 10012 | 6455 | 14287 | 9617 |

奶酪后两次解析均使用新的 `playerContent` 调用及诊断 SID。媒体阶段记录 `EXTENDED_MEDIA_UNAUTHORIZED_LOCAL`，没有绕过归属检查继续访问。当前真实详情只有一条线路，记录 `alternate_line_available=false`，没有伪造备用线路。

## 失败证据及边界

- 玩偶哥哥原始失败为 `Spider JSONException: End of input at character 0`。正常首页尝试仍返回“首页及0个分类均无影片”，之后 `search_after_home` 记录站点未就绪。首页步骤的请求完成不代表首页有效，查看独立 `home_browse_failure` 字段。
- 只读检查已验证JAR的公开方法声明：两个节点继承的 `BaseSpiderGuard` 都声明两参数、三参数 `searchContent`。固定上游 FongMi 的第一页也调用两参数接口，未无依据替换方法或尝试解密保护代码。
- 奶酪解析得到的是当前批准站点的本机代理URI，健康检查 READY。代理回调记录为长度3的 `Integer(200), String(HLS), ByteArrayInputStream`，这仅证明接口形状，不证明内容是有效媒体。
- `HLS_MANIFEST_REJECTED`、`PROXY_CALLBACK_OR_REWRITE_FAILED` 来自宿主HLS处理，随后本机HTTP502。不是饭太硬服务器HTTP502，也不是HTTP200有效视频。当前仍未确定具体是无效清单内容还是清单内引用不能安全重绑定；未放宽HLS、跨站、本机归属或授权校验。
- 保留正式媒体探测的默认时限失败。额外诊断使用同一个生产 `HerrinRemoteMediaProbe`、相同HTTP/Range/媒体/归属校验，仅增加诊断请求预算以取得HTTP结果，不把扩大时限后的结果写成原时限通过。
- 测试调用正式 `TvBoxSpiderBridge`，准备的出帧路径为生产 `AuthorizedPlaybackPanel → HerrinPlaybackAdapter → 固定上游 FongMi PlayerManager → Media3`，没有测试自建ExoPlayer。媒体门禁失败时不强行启动播放器；本次无出帧、Seek或持续播放成功。
- 授权UI若真实出现，测试会在内存中解码实际显示的QR，验证取消及Back，然后停止需要私人登录的后续环节。不关闭安全窗口，不提交私人登录、不保存二维码/Token/媒体URL。此次未实际进入该分支，不能声称授权流程已通过。
- 没有 MockWebServer、Mock 视频、授权绕过、插件修改、ABI属性伪装或 Superpowers。

## 修改范围和验证

新建 opt-in `FanTaiYingRealNodesTest`、原创 ARM JNI canary、独立矩阵工作流、运行脚本及只读公开方法声明诊断。普通构建不会打包 canary 或编译执行 live 测试。
生产改动仅为代理回调的脱敏结构/固定错误码记录，保留原始返回对象、异常、HLS拒绝、请求授权及既定超时。针对性9项JVM用例覆盖回调记录失败时的身份保持、字段隐私、原有代理行为及HLS拒绝不可被诊断回调改变。

本轮先在独立测试分支收敛上述真实专项，随后才把代码追加到 Draft PR #84，统一运行常规完整回归。完整回归结果以PR最新提交的Actions为准，受控测试通过不替代本报告的两节点失败结论。没有合并主分支。

## 工件与复现

- [ARM64 JSON/方法声明/ABI证据](https://github.com/shawnhhh/aura-tv/actions/runs/42778911339/artifacts/13745781001)
- [ARMv7 JSON/方法声明/ABI证据](https://github.com/shawnhhh/aura-tv/actions/runs/42778911339/artifacts/13745975258)
- [三组成对APK及构建日志](https://github.com/shawnhhh/aura-tv/actions/runs/42778911339/artifacts/13745920064)
- [ARM64完整系统镜像启动评估](https://github.com/shawnhhh/aura-tv/actions/runs/42778911339/artifacts/13745586099)

工件中的每节点JSON保留阶段耗时、错误码、设备ABI、真实siteKey、初始化结果、出帧/Seek布尔证据、独立选集失败和代理回调形状；不包含媒体URL、Cookie、二维码像素或插件响应体。

在已配置Android SDK/NDK、Java17/Gradle8.9、单一专用测试设备（可用ANDROID_SERIAL选择）的环境，从仓库根目录执行：

```sh
ARM_PACKAGE=true LIVE_INSTALL_ABI=arm64-v8a bash android-tv/scripts/run_live_nodes.sh
# Google APIs x86_64 的 ARMv7 转译：
ARM_PACKAGE=true LIVE_INSTALL_ABI=armeabi-v7a bash android-tv/scripts/run_live_nodes.sh
```

测试脚本安装并运行测试包，请使用干净的专用设备/测试用户。无需并且不要在聊天或工件中提供私人登录凭据。
