# P0 Cloud 断点（当前）

更新时间：2026-11-05 UTC。Codex Cloud、单代理串行；未使用 Superpowers。

## HEAD / 并发 / PR

- 本轮开始远端 HEAD、当前已验证的代码 HEAD：`51fc0f282bc02bd05295444b4612769ad8f7187e`。
- 最近完成开发提交：`cf27546ce627d7cbadaa456f3cf1bbfbb054a1e8`；最近断点提交：`51fc0f2`。
- 本轮仅增加构建恢复文档、实际失败日志和本断点；随后文档提交的最终 HEAD 用
  `git rev-parse HEAD` 核对，不把该文档提交写成已通过编译的代码 SHA。
- 分支仍为 `codex/m471-tmdb-personal-to-tvbox-playback-20261102`；PR #80 Draft、未合并。
- 恢复前工作区干净，只有一个 worktree；开始 fetch 与提交前 ls-remote 均为 `51fc0f2`，
  未发现外部提交。不覆盖并发工作，不合并 main 或其他 PR。

## 当前阶段与本轮小任务

阶段 A 的兼容核对已完成；当前只恢复云端 SDK 和现有代码的构建验证。
**SDK 缺失已解除，验证被新的 JitPack 网络阻塞挡住；没有推进 B/C/D。**

实际安装并核实：官方 SDK command-line tools 23.0.0、API 35 revision 2、
Build Tools 35.0.0、Platform Tools 37.0.1；AGP 自动另装 Build Tools 34.0.0。
官方 command-line tools ZIP 通过仓库 XML 中的 SHA-1 校验后才使用。
Gradle wrapper 8.9 可运行；SDK、Gradle 和用户目录均为云端可写缓存。
未执行外部第三方 Spider JAR/JS/Python，未修改生产播放代码或 UI。

可重现的安装来源、缓存路径和命令见 [cloud-build.md](cloud-build.md)。
环境重建可能清空缓存，恢复时应检查实际文件，不重新执行已完成的源码兼容核对。

## 本轮验证与 CI

在 `51fc0f2` 上执行：

`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain --max-workers=2`

- SDK 定位、AGP 配置通过，自动安装 Build Tools 34.0.0 成功。
- `:app:checkDebugAarMetadata` **失败**，退出 1；总耗时 3m27s。
- `com.github.thegrizzlylabs:sardine-android:0.9` 请求
  `https://jitpack.io/com/github/thegrizzlylabs/sardine-android/0.9/sardine-android-0.9.pom`
  返回 HTTP **403 Forbidden**。
- 独立 curl CONNECT 同样被代理返回 403；官方环境状态显示网络策略 enforced、
  observations_current=true，但 effective_allowed_hosts 无 jitpack.io。
- 未开始执行项目编译、单元测试和 lint，未生成 APK。Gradle 的
  `checkKotlinGradlePluginConfigurationErrors SKIPPED` 是工具自身空检查，不是跳过播放测试；
  没有禁用任何项目任务、测试或依赖。
- 本轮未重跑上一轮已通过且未变更的静态检查/检查器测试。`git diff --check` 通过。
- 开始时当前 HEAD 的 Actions runs 为空，PR 仍 mergeable=false；最新代码的 CI 成功
  仍缺失，不能引用 `ebd67b32` 的旧成功记录替代。
- 实际命令输出：[2026-11-05-cloud-build.log](validation/2026-11-05-cloud-build.log)。
- 无 Android TV Emulator 或 TCL T7L Ultra 新验收；P0 未完成。

## 当前阻塞 / 下一条明确操作

**先在当前云端环境配置界面，为网络白名单增加 jitpack.io。**
保留现有规则、代理与 CA。可用工具没有网络配置修改入口；不通过其他镜像、
直连或删除依赖绕过策略。该变更须经平台环境配置流程生效。

放行后先读取本断点和远端 HEAD/PR/CI，检查并发修改、SDK/cache 是否仍在。
再用 cloud-build.md 的环境变量运行同一测试/lint/assemble 命令，优先修复实际失败，
尤其 ParseJob 启动/期限回归；不要重复 SDK 安装（缓存仍存在时），不要跳到 C/D。
完整播放器仍有既有定制 Media3/固定版本 ParseJob 差异缺口，详见兼容矩阵。

## 额度 / 续跑

本轮开始与结束检查可用官方工具：无额度百分比、剩余限额或恢复时间查询接口，
未收到额度不足提示，不虚构数值。按用户规则只执行一个 SDK/构建恢复小任务，
当前因真实网络阻塞暂停。未设置定时自动化，也不承诺后台自动恢复。
网络配置更新后等待用户或外部任务再次调用。

---

以下为上一轮历史记录，恢复操作以上方“下一条明确操作”为准；
历史 SDK 缺失描述已被本轮安装结果取代。

# 2026-11-04 历史断点

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
