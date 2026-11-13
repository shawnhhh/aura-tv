# P0 Android 执行通道

入口：`.github/workflows/herrin-p0-real-playback.yml`，固定开发分支
`codex/herrin-p0-live-playback-20261110`。不依赖Codex本地Android工具。

- 自动：修改 `.github/p0-playback-trigger.json` 的 attempt/reason，普通提交并push。
  默认两个节点、标准armeabi-v7a包、API30 Google APIs x86_64 ARM转译模拟器。
  文件是执行触发器，nodes/abi记录自动运行默认值，不是动态配置解析器。
  普通源码提交不自动重发来源请求；确认有修复/新环境依据后才更新触发文件。
- 手动：Actions → HERRIN P0 Real Playback → Run workflow → 选择当前P0分支，
  node=both/cheese/wanou、film=both/xiaocheng/shinchan、abi=armeabi-v7a/arm64-v8a。
- A完整JVM/Lint/编译与B诊断构建独立；C仅依赖B，两个节点fail-fast=false；
  完整节点运行中D始终汇总，各Job失败保留整体失败。完整TV/生命周期复用android-emulator工作流独立执行。
- M476只补发正文IO失败阶段事件，NETWORK_ERROR/原超时/原断言均保留。
- B自动安装JDK17、Gradle8.9、SDK35/build-tools35/NDK，输出内部诊断APK（14天）。
  C验证KVM、启动模拟器，安装真实包及AndroidTest，通过现有正式MainActivity驱动执行。
- 工件：p0-full-validation保存原JUnit/Lint；p0-node-cheese/wanou保存节点JSON、
  实际安装/ABI、严格节点JUnit、logcat脱敏分类及environment-checkpoint.json；
  p0-environment-checkpoint保存D汇总（含精确SHA/run/event和全部Job结果）。
  原始logcat/QR/令牌/媒体地址不持久化，复用既有分类器；不发布验收APK。
- D调用既有verify_live_node_acceptance.py并额外要求REAL_PLAYBACK_PASS；授权待扫码
  不算播放通过。缺工件/未启动/未安装/未执行不会变成成功。

首次push后须通过GitHub实际run/jobs/logs验证执行，不以YAML检查通过宣称设备运行。
若账户拒绝执行：仓库Settings → Actions → General启用Actions并允许上述Actions，
检查账户Actions额度/计费、仓库写权限和组织Runner策略。push自动触发不需要本地
Actions API token；CLI手动触发才需要有Actions写权限的已授权身份。

原生ARM设备准备复用同一工作流：`diagnostic_only=true`执行完整A/B/TV及原创媒体
基准、不重发受阻节点；`artifact_only=true`仅重新构建B。两种模式均明确不执行节点验收，
不能记REAL_PLAYBACK_PASS；默认false保留原完整严格验收。push分别用提交标记
`[diagnostic-build-only]`或`[artifact-only]`，ARM64用`[arm64]`。触发文件无需为构建而改动。
B的`diagnostic-manifest.json`绑定完整源码SHA、两个APK的SHA256和每个ELF的真实机器类型。

准备设备端命令（先下载相应run的`p0-internal-diagnostic-apks`，保留`arm64-v8a/`目录）：

```sh
gh run download RUN_ID -R shawnhhh/aura-tv -n p0-internal-diagnostic-apks -D diagnostic-apks
bash android-tv/scripts/run_fantaiying_arm_e2e.sh --p0-native prepare \
  --artifact diagnostic-apks/arm64-v8a --sha APK_SOURCE_SHA --serial DEVICE_SERIAL
```

`prepare`只读设备属性并验证APK，**不安装、不清理配置、不运行Spider**；无需root。
检查ABI必须原生ARM64、API≥26、无x86转译及qemu。序列号只用于明确选设备，不写入报告。
没有连接设备时不能声称已准备或通过。普通TCL日常使用环境应停在这一步。

只有已经隔离的一次性设备/测试用户，才运行会重置`tv.herrin.app`测试偏好的正式MainActivity驱动：

```sh
bash android-tv/scripts/run_fantaiying_arm_e2e.sh --p0-native run-on-disposable-device \
  --artifact diagnostic-apks/arm64-v8a --sha APK_SOURCE_SHA --serial DEVICE_SERIAL \
  --node cheese --film shinchan
```

源码checkout必须等于APK_SOURCE_SHA；可用该P0提交的独立detached worktree，不新建开发分支。
切勿在含日常HERRIN配置的电视执行此命令。不会访问其他应用数据、root或关闭安全机制。
首次失败即保留报告，不继续请求第2/3集。玩偶用`--node wanou`，另一影片用`--film xiaocheng`；
只在新的环境/授权依据下执行。证据保存于`native-device-evidence/SHA12-node-film/`。
再次运行前保留并移走仓库`evidence/`，驱动不会静默覆盖旧证据。

旧崩溃证据只留信号/模块分类，无法补回PC/寄存器。一次性模拟器的新采集器只导出这两个授权播放包的
crash buffer记录，保留相对PC、实际共享库basename、Build ID、数值寄存器和信号；不导出
私有路径、abort正文、内存内容、URL/账号或其他应用日志。不提取官方APK充当依赖。
真实设备模式不读取或清理全局logcat，只使用本应用文件及本UID的ApplicationExitInfo（API30+），
限定本次开始后的`:spider`进程退出原因/状态/时间。未公开的完整栈标记证据缺口，不因未读而报“无崩溃”。
有对应Build ID的合法ELF/调试符号后，才可用NDK llvm-symbolizer/ndk-stack定位；没有时不得猜函数。

同一一次性原生ARM64设备的原版参照安装（未改包/未重签名）：

```sh
curl --fail --location --proto '=https' \
  https://github.com/FongMi/Release/releases/download/5.8.7/leanback-arm64_v8a.apk -o official-fongmi-5.8.7.apk
printf '%s\n' 'cda5776ba2ee6f7dfdcd3d7c1a13095ba8672b9892d6fb9e1ba38f993ad66f3a  official-fongmi-5.8.7.apk' | sha256sum --check
adb -s DEVICE_SERIAL install --abi arm64-v8a -r official-fongmi-5.8.7.apk
```

设备端用原版公开设置界面导入同一饭太硬仓库，只勾选当前节点，确认实际第九季/真实集数；
按同一网络和合法授权分别观察首帧/60秒/控制。不要在日常FongMi安装上替换配置。
现有原版CI脚本只适用于一次性模拟器，**不要直接在日常电视运行**：它会读取模拟器crash buffer。
两个应用的版本、ABI、配置声明JAR MD5和授权状态必须分别记录；不同快照不当严格A/B。
安装、启动、搜索、Media3 READY或工作流Success都不是原版出帧证明。

原创素材播放器基准位于既有`PlaybackDiagnosticsIntegrationTest`，使用生产
AuthorizedPlaybackPanel/HerrinPlayerSession和真实Media3。六秒原创MP4/HLS按1x循环，各观察
60秒真实墙钟、持续解码、实际HTTP读取、画面、暂停/Seek/释放，结果在
`player-baseline.json`。这只证明播放器能力，不能替代任一饭太硬节点。
