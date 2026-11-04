# P0 Cloud 断点

更新时间：2026-11-04 UTC。仅 Codex Cloud，单代理严格串行；未使用 Superpowers。

## HEAD 与恢复检查

- 分支：`codex/m471-tmdb-personal-to-tvbox-playback-20261102`；PR #80 保持 Draft。
- 本轮开始远端 HEAD：`ed6217312e88b9cb0abd0edaf5af5331776d3906`。
- 当前开发 HEAD / 最近完成提交：`cf27546ce627d7cbadaa456f3cf1bbfbb054a1e8`
  （阶段 A 兼容核对、生产 source set 旧链路契约及静态检查误报修复）。
- 此文档随后单独提交；最终分支 HEAD 应以 `git rev-parse HEAD` 和远端 ref 为准，
  不把文档自身 SHA 当作已经运行 Android 验证的代码 SHA。
- 拉取前工作区干净，仅一个 worktree；提交前两次查询远端仍为 `ed621731`，未发现
  外部提交。本轮无 merge、force push、UI 或生产播放代码修改。
- 已读取现有整体替换计划、旧矩阵、固定上游暂存和实际生产入口；现状见
  [兼容核对](compatibility-matrix.md)，不要重复迁移已退出 main 的旧代理。

## 当前阶段 / 已完成的小任务

阶段 A：七个核心环节的生产兼容核对及旧核心不得重入生产的契约检查已完成。
未宣称阶段 B/C/D 或完整 P0 通过。阶段验证仍有 Android 构建环境阻塞。

已确认：Config/Loader 为宿主实现；Spider 使用 NativeFongMiProxy；旧代理仅测试；
运行 ParseJob 仍标早期上游来源；固定版本 PlayerManager/MediaItemFactory 仍暂存且
依赖定制 Media3 API。不得把原版 blob 校验说成完整播放移植。

## 验证与 CI

本轮实际执行：

- `python3 android-tv/scripts/check_static.py`：通过，包括 43 份固定原版 Git blob 校验
  及所有生产 source set 旧链路检查。
- `python3 -m unittest discover -s android-tv/scripts -p 'test_check_playback_boundary.py' -v`：
  3/3 通过；已接入 Android Debug 工作流。
- `python3 -m unittest discover -s scripts/m467 -p test_verify_native_kit.py -v`：14/14 通过。
  这些是检查器测试，包含故意损坏依赖的 fixture，不能解读为真实 native kit 已齐备。
- `git diff --check`：通过。
- 首次静态检查因 Bridge 注释含 WebView 失败；已修复检查器区分注释/代码，
  新增回归仍明确检查实际 WebView 代码保留，未删除关键播放测试。
- Debug 构建：默认缓存目录只读；设置可写 GRADLE_USER_HOME 后下载官方 Gradle 8.9；
  在线解析 AGP 8.7.3 成功后，ANDROID_USER_HOME 也需设为可写目录。
  最终命令使用 `/tmp/aura-p0-gradle`、`/tmp/aura-p0-android`，执行
  `./gradlew :app:assembleDebug --offline --console=plain`，退出 1：**SDK location not found**。
  未发现 ANDROID_HOME/ANDROID_SDK_ROOT 或 local.properties，未生成 APK。
- 在线 Java 网络使用会话 proxy:8080，保留 TLS 验证。Google Maven POM HTTP 200；
  不能把当前阻塞说成该域名被拒绝。未执行外部第三方 Spider JAR/JS/Python。
- 开始时 HEAD `ed621731` 的 workflow runs 及 combined statuses 均为空；最近分支可见
  `ebd67b32` 的 Debug `41819258636`、TV Emulator `41819258904` 为 success，
  不覆盖之后 16 个提交。新提交 CI 尚待查询；不能继承旧提交的成功结论。
- TCL T7L Ultra 未实测。无当前 HEAD 的模拟器/真机 MP4、HLS、TS 出帧结论。

## 阻塞与下一条操作

真实阻塞：云端缺 Android SDK，当前 Debug 构建无法继续；上游完整播放器还存在
已列明的定制 Media3 依赖缺口。不得跳过这些门槛进入 C/D 或宣称整体替换完成。

下一轮先读取此文件、远端 HEAD/PR/CI，检查并发修改。然后仅处理一个小任务：
在可写云端目录配置官方 Android SDK（API 35/build-tools 35.0.0，保留代理/CA），
恢复 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`，优先修复真实失败，
尤其最新 ParseJob 启动/期限回归；取得对应 SHA 的验证结果后再推进固定版本差异闭合。
不要重跑已通过的纯文档核对，不覆盖外部提交，不合并 main/任何 PR。

## 额度与续跑

本轮开始及收尾检查可用官方工具和 Codex CLI 支持入口：未提供可准确读取的额度
百分比、限额状态或恢复时间；未出现额度不足提示。不虚构百分比或五小时后的时间。
按用户规则仅执行上述一个边界明确的小任务，当前保留断点停止。
有自动化工具可创建定时任务，但本轮没有可验证的额度不足/恢复时间，未新建自动化，
不承诺后台自动恢复。等待用户或已有外部定时任务重新调用。
