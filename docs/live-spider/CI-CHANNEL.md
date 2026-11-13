# P0 Android 执行通道

入口：`.github/workflows/herrin-p0-real-playback.yml`，固定开发分支
`codex/herrin-p0-live-playback-20261110`。不依赖Codex本地Android工具。

- 自动：修改 `.github/p0-playback-trigger.json` 的 attempt/reason，普通提交并push。
  默认两个节点、标准armeabi-v7a包、API30 Google APIs x86_64 ARM转译模拟器。
  文件是执行触发器，nodes/abi记录自动运行默认值，不是动态配置解析器。
  普通源码提交不自动重发来源请求；确认有修复/新环境依据后才更新触发文件。
- 手动：Actions → HERRIN P0 Real Playback → Run workflow → 选择当前P0分支，
  node=both/cheese/wanou、abi=armeabi-v7a/arm64-v8a。
- A完整JVM/Lint/编译与B诊断构建独立；C仅依赖B，两个节点fail-fast=false；
  D始终汇总，各Job失败保留整体失败。完整TV/生命周期复用android-emulator工作流独立执行。
- M476只补发正文IO失败阶段事件，NETWORK_ERROR/原超时/原断言均保留。
- B自动安装JDK17、Gradle8.9、SDK35/build-tools35/NDK，输出内部诊断APK（3天）。
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
