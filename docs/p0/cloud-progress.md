# P0 Cloud 断点

更新时间：2026-11-05 UTC。Codex Cloud，单代理串行；未使用 Superpowers。

## HEAD、分支与并发

- 分支：`codex/m471-tmdb-personal-to-tvbox-playback-20261102`；PR #80 保持 Draft，不合并。
- 本轮恢复起点：`b75e1f947dcfeb933ab332a19c73bdb883b757f4`。
- 最近已完成并推送的子任务：`8811c7575b37abadb19f39a3ed3c1ea0ee197dde`，Sardine 源码构建恢复。
- 记录时 HEAD：`8811c7575b37abadb19f39a3ed3c1ea0ee197dde`；下面第二项实质修复与本断点
  随下一提交保存。最终 HEAD 用 `git rev-parse HEAD` 和远端 ref 核对；APK 交付目录的
  manifest.json 记录其完整提交 SHA、源码树 SHA 和 APK SHA-256，不用旧提交冒充最终实现。
- 恢复前工作区干净、仅一个 worktree；每次提交前查询远端未发现外部更新，普通 push，
  未 force、未覆盖别人提交。没有改已验收首页、TMDB UI、NAS/云盘或第三方插件授权。

## 已完成：构建恢复（阶段 A 验证补齐）

JitPack 403 已确定为当前云端网络策略限制；只有官方状态/诊断读取工具，无网络配置写入口。
用户授权评估等效依赖后，改为在 app 中编译官方 Sardine v0.9 的全部 90 份原始 Java 源码，
固定提交 `d0af7ae8e7ee0654a763c4c6f638a5e98b1782e9`，保留 Apache-2.0、API、同版本
OkHttp/Simple XML 依赖和原 ProGuard 规则。APK 携带许可证；静态检查逐文件校验 Git blob。
没有删除 WebDAV 依赖功能，没有使用镜像、修改网络策略或绕过代理。

SDK 已恢复；环境原有 OpenJDK 21 缺 jlink，现已安装校验过的官方完整 Temurin JDK 17。
路径、工具版本、官方校验和与复现命令见 [cloud-build.md](cloud-build.md)。缓存可能随环境
重建消失，先检查实际文件。新源码依赖的真实 PROPFIND/XML、认证 Range/媒体字节和 403
回归均通过。既有 ParseJobDeadlineTest 的 finally 清理断言竞态已增加独立关闭事件等待；
保留超时、失败次数和关闭次数断言，没有删除测试或放宽生产超时。

8811c75 的本地完整测试/lint/Debug 构建通过，532 项测试，0 失败/错误/跳过。
失败与成功证据保存在 validation/，见 source-build-missing-jlink、parsejob-cleanup-race、
source-build-success 日志。不是只做文档记录；已提交源码构建配置及全部固定依赖源码。

## 已完成：ParseJob 宿主派发拒绝清理（阶段 B 的小任务）

复核固定 FongMi 上游 ParseJob.complete/deliverResult/stop：原版 App.post 被注入 Host.post
替代后，宿主可能拒绝派发，导致 deliverResult 的 finally 根本不执行。已有 done 标记还会
阻止后续截止时间回调，工作池和活动 WebView 因而残留。

- 新增真实 ParseJob 生命周期回归：先注册 WebView，随后拒绝最终结果；修复前稳定失败，
  错误为 parser worker must shut down immediately（红灯日志保留）。
- complete 捕获派发 RuntimeException/Error，立即 stop，保留原异常；不在错误线程派发 UI
  回调。回归同时要求两个工作池关闭、WebView 恰好关闭一次、重复 stop 保持幂等。
- 实际 Android 宿主通过 ParseUiDispatcher 检测 Handler.post 的 false 返回，转换为同一
  拒绝异常；回归使用这个实际 boolean 队列桥接，而不是仅让测试宿主抛异常。
- 保留 FongMi GPL-3.0 原作者与旧来源记录，增加宿主修改日期；43 份固定上游文件未改动。
  这是宿主适配修复，不宣称整套 Config/Loader/PlayerManager 已完整移植。

## 最终验证与产物

实际执行 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`，退出 0，
**BUILD SUCCESSFUL**。最终 JUnit XML：**533 项，0 失败、0 错误、0 跳过**；
lint 0 error、30 warning；Debug APK 已重新生成，内含 Sardine 许可证。
ParseJob 定向回归通过后才执行完整验证；没有运行未授权外部 Spider 插件。

- `validation/2026-11-05-parsejob-completion-red.log`：修复前确定性失败。
- `validation/2026-11-05-parsejob-dispatch-targeted.log`：最终宿主桥接定向测试。
- `validation/2026-11-05-parsejob-dispatch-success.log`：最终完整验证成功。
- 静态检查通过：43 份原版 FongMi、90 份原版 Sardine、旧代理生产边界。
- 产物保存在云端 `/workspace/artifacts/HERRIN-P0-<最终HEAD>.apk` 及 manifest.json；
  不提交 APK/SDK/密钥到 Git。当前代码出帧、声音、遥控器的 TCL 验收仍未发生。

## CI、剩余范围与下一条操作

GitHub Actions：8811c75 的 workflow runs 为空，PR mergeable=false；不能把本地成功当作
当前 HEAD 的远端 Debug/Emulator 成功。现有 push 分支筛选不含当前 P0 分支，这是可独立
继续修复的 CI 触发缺口，不能以合并 base/main 来解决，也不能删关键模拟器测试。

下一轮先读取远端 HEAD、此断点和 CI，检查并发。下一条明确操作：恢复当前 P0 分支的
串行 CI 入口，复用现有 Debug/TV Emulator 检查，要求 Debug 成功后才运行模拟器，并以
实际 SHA 记录 APK/测试结果。然后继续固定上游 ParseJob/Config/Loader 的接口差异闭合。
完整 PlayerManager/MediaItemFactory 仍依赖定制 Media3，见兼容矩阵，未进入 C/D 验收。

没有新的下载阻塞需要反复重试；不再要求用户先放行 jitpack.io。仍未取得最新提交的 TV
Emulator 或 TCL 真机真实 MP4/HLS/TS 出帧、声音和遥控器闭环证据，**P0 未完成**。

## 额度与续跑

本轮开始、首个子任务结束及收尾均检查官方可用工具：无准确额度/限额/恢复时间接口，
未出现额度不足提示，不虚构百分比。单轮完成两个串行且可测试的小任务后保存断点，
避免继续扩展大型移植。未创建定时自动化，不承诺后台自行恢复。
