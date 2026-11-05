# P0 Cloud 断点

## 当前：快速集成 B+C 生产接线（2026-11-05）

- 模式已按用户新指令切换：连续集成、阶段性提交、一次串行 CI；不再逐小项全测和停止。
- 已核实基线 `ca038f80cdcd96618744fabcbd17d05fda402938`，CI 41982527946 success，PR #80 Draft。
- 本批生产代码：Film/线路 HMAC 来源归属及 IPC；按 JAR+批准会话复用 ClassLoader；
  Config→Spider→playerContent 带同一批准凭据；同 JAR 的 Init 缓存静态代理地址受控重绑定。
- 已接唯一生产播放器：AuthorizedPlaybackPanel → HerrinPlaybackAdapter → PlayerManager
  → MediaItemFactory → 现有真实 ExoMediaSourceFactory/Media3；准备、重试、换线、关闭已接管。
  旧 Java 媒体会话委托同一核心，移除第二处 Exo Builder；Spider 故障直链回退移入 test。
- 首页/TMDB UI、NAS→云盘→TVBox 顺序未改；未重做已验收生命周期，未执行外部插件。
- 集成编译/定向回归通过；统一静态检查、13 项检查器回归、546 项 JVM 全量（无失败/错误/跳过）、
  lint、Debug/测试 APK 均 success。只阶段性 push 一次，再检查该 SHA 的 Debug→Emulator。
- 实际移植范围及依赖差异见 player-core-integration.md；不把未提供的定制 Media3/MPV
  用 stub 替代。TCL/外部已授权真实源仍未实测，P0 未完成。
- 无可靠额度接口或恢复时间；无不足提示，不虚构百分比，不承诺定时自醒。断点保持简短。


## 当前：Worker 关闭后晚到 init 的生产修复（2026-11-05）

- 实现前 HEAD：`c38cc7c84bacd5e230338ac9e89257702614025b`；恢复基线 45e64c1 的
  Debug/Emulator 已全通过（541 JVM / 181 TV）。PR #80 保持 Draft，无合并、无其他工作树。
- c38cc7c 是先提交的真实 DEX 复现：串行 CI
  [41979149789](https://github.com/shawnhhh/aura-tv/actions/runs/41979149789) Debug success，
  TV 185 项中仅新增四项失败、0 错误/跳过；原 181 项通过。四条生产失败是关闭后 bootstrap
  仍成功、取消后 active 残留、旧 init 成功覆盖新站点、关闭后 init 仍返回 Ready。没有 fixture
  超时或装载失败。见 validation/2026-11-05-worker-dex-red.json，不删除这些测试求绿。
- 实质修改 TvBoxSpiderWorker：在派发前捕获资源 epoch；保留同有效 sessionKey 复用；
  原子分离/失效、初始化代理归属、bootstrap/init/publish/config/home 检查；close 先清状态，
  再在 monitor 外清 proxy/Spider/HTTP/runtime。晚到实例不能发布 active/loader/proxy；旧
  清理不能清新会话 CatVodProxy。重复 close 不重复 destroy，ClassLoader 移除宿主引用，
  没有虚构 DEX 卸载 API。与固定上游 checkOpen/cacheSpider 对照见 loader-lifecycle-audit.md。
- 未重做来源意图撤销、ParseJob、默认 Super、Sardine 或 CI 恢复。worker 资源 epoch 独立
  于 bridge source/transport；原批准描述控制合法重建，未放宽 mayRun、HTTPS/MD5/DEX
  校验或外部运行时授权。没有改首页/TMDB UI，没有旧 do= 补丁，保留 GPL/原作者修改记录。
- 四项原创 DEX 设备回归使用 CountDownLatch 精确关卡，无 Thread.sleep 或时间竞争；
  覆盖 bootstrap/init/取消/旧任务返回新站点、引用/端口释放、重复关闭、重新应用及复用。
  旧端口断言在新 listener 创建前执行，避免操作系统合法端口复用误报。未执行第三方插件。
- 本地按顺序定向 → 静态（含门禁检查器4项）→ JVM全量 → lint → Debug/测试APK 编译通过：
  **541 JVM、0 失败/错误/跳过**，lint 0 error。最后仅设备断言顺序调整后补跑 lint/APK 编译。
  证据：validation/2026-11-05-worker-{targeted,static,jvm,lint,debug}.log。
- 此断点随生产代码提交；完整最终 SHA 用 git rev-parse HEAD 核对。本机没有 Emulator/KVM，
  实际 DEX 绿灯必须等修复 SHA 的 P0 Serial Verification：先 Debug success，再 Emulator
  的四个新用例/完整媒体音轨遥控器门禁。不能继承旧提交 success 或把 JVM 当设备验收。
  SHA 专属最终 CI/产物回执保存在 /workspace/artifacts/ci-<SHA前7位>.json 和 manifest。
- 下一条操作：push 后按最新 SHA 查询 actions/runs，读真实结论；失败优先修复。通过后
  下一小任务为 **B1 Film 详情与 playerContent 的完整来源归属**，再按 B2/B3/B4/B5 顺序。
  此修复仅对应已复现的 worker 晚到 init 契约，不宣称 Loader/阶段 B/P0 全部完成。
- 官方准确额度接口不可用、无不足提示；只按有边界小任务推进，不虚构额度或恢复时间。
  未配置后台定时续跑，不承诺会自行唤醒。TCL T7L Ultra 未实测。


## 当前：Worker 晚到 init 确定性复现（2026-11-05，修复前）

- 恢复 HEAD：`45e64c1e6ef4135af2a4738bdcd9f5562bfe083a`，工作区干净、PR #80 Draft。
  此提交串行 CI [41963240319](https://github.com/shawnhhh/aura-tv/actions/runs/41963240319)
  已全 success：541 JVM / 181 TV，0 失败/错误/跳过；Debug 03:47:25Z 完成后 Emulator
  03:47:27Z 开始。两项来源撤销 Binder 用例真实通过。回执在 validation/2026-11-05-ci-45e64c1.json。
- 继续同一 Loader 专项，未重复来源意图修复。实际 worker 的 JAR Init/Spider.init 返回后
  仍无关闭检查，直接写 active/loader/proxy；固定 SpiderLoader.checkOpen/cacheSpider 则检查关闭并退休晚到实例。
- 已加仓库原创 DEX fixture 的 bootstrap/init CountDownLatch 关卡和计数；没有外部插件、
  Thread.sleep 或竞态轮询。新增四项实际 worker 设备回归：关闭后晚到 init、bootstrap
  结束禁止继续构造、忽略中断的取消、旧 init 返回不能覆盖新授权站点/清新代理。
  还检查只销毁一次、清空 loader/HTTP/实例引用、代理端口关闭、合法重新应用和同会话复用。
- 生产代码尚未修改。当前云端无 Emulator 或 /dev/kvm，真实 DEX 红灯只能在现有 GitHub
  串行 Emulator 执行；本提交是可复现回归检查点，不声称修复或设备测试通过。
  本地定向/静态/JVM 全量(541)/lint/Debug 与测试 APK 编译通过，详见 worker-red-*.log。
- 下一条操作：push 此复现提交，查询该 SHA 的串行 CI，Debug 成功后读 Emulator 的四项
  失败证据；确认是生产生命周期失败后，只修该缺口，再完整顺序验证及提交。不得删测求绿。
- 官方准确额度接口不可用，未收到不足提示；本轮只完成 worker 生命周期小任务。不虚构
  百分比或恢复时间，不承诺自动后台唤醒。阶段 B/P0 均未完成，TCL 未实测。


## 当前：Loader 来源意图与晚到回调子契约（2026-11-05）

- 恢复及提交前核实的 HEAD：`2cef56b629b86952ce7abddf2bcf5042ce5cc096`。
  PR #80 Draft、未合并，远端无外部更新，工作区起点干净。基线串行 CI 41957142681 success，
  536 JVM / 179 TV，0 失败/错误/跳过；回执已补入 validation/2026-11-05-ci-2cef56b.json。
- 已逐项读取固定 FongMi 305dff2 的 BaseLoader/JarLoader/SpiderLoader 与当前
  TvBoxSpiderWorker、bridge、JarCache、授权和 IPC 路径。详见 [Loader 核查](loader-lifecycle-audit.md)。
- 本轮只修复一个实际缺口：初次 BROWSE 无条件写 lastBrowse，关闭后晚到成功可重新发布
  失效来源；关闭前排队的应用请求也可在关闭后绑定/发送，重新触发加载。
- 实质修改：SpiderSourceSession 原子 epoch/发布/撤销；bridge 在首次挂起前捕获意图，
  Mutex 等待后、绑定前后、发送及结果发布检查同一意图；transport generation 独立保留。
  close 立即撤销来源，再执行主线程解绑；不阻止已批准来源的正常运输重建，旧请求不能
  自动取得新 epoch。没有改已验收 UI、Sardine、ParseJob 修复、默认 Super 或旧 do= 路径。
- 回归先红后绿：5 项原创来源状态 JVM 测试中原先 2 项失败（晚到发布、撤销前排队）。
  修复后 Loader/现有兼容定向测试通过。新增两项真实 bridge + 原创 Binder fixture 设备
  回归，分别控制第一次主线程派发前、Binder success 后的关闭窗口；断言拒绝、无重绑/重放、
  新显式站点成功、重复 close 只解绑一次。fixture 不加载插件，新类加入 TV 必跑门禁。
- 本地按顺序完成：定向 → 静态（含 4 项完整套件报告器回归）→ JVM 全量 → lint →
  Debug APK / instrumentation APK 编译，均 success。JVM **541 项、0 失败/错误/跳过**。
  最后仅为设备 fixture 的失败清理完善 dispatcher 释放后，重跑 lint 和 APK 编译通过；
  生产/JVM 输入未再变化。原本体取消/资源/JSON ABI 回归仍保留，没有删测或跳过。
- 证据：validation/2026-11-05-loader-{session-red,targeted,static,jvm,lint,debug}.log。
  所有源码修改、GPL 说明和此断点随同一实现提交保存；最终 SHA 用 git rev-parse HEAD 核对。
- push 后下一条操作：按新 HEAD 查询 P0 Serial Verification，等 Debug success 后再检查
  Emulator；核实 2 个新设备用例和完整媒体门禁。不能继承 2cef56b 的远端成功。
  最终 CI/产物回执保存在云端 artifacts 的 SHA 专属 JSON，恢复时也应重新查询远端结果。
- 下一项尚未闭合：先用原创 DEX fixture 延迟 init 复现 worker.close 后的 active/loader/
  临时 proxy 晚到发布，再补与上游 checkOpen 等价的 worker 生命周期边界。之后核查 Film
  详情/playerContent 的完整来源归属；本轮未宣称 Loader/阶段 B 全部完成，不进入 C/D。
- 额度官方接口仍不可用、无不足提示；不虚构数值，不承诺后台唤醒。本轮按一个边界明确的
  Loader 子契约执行，后续只在验证完成且额度可用时推进。TCL 未实测，P0 未完成。

以下是此前任务的历史断点，恢复操作以上方最新条目为准。

## 当前：CI 恢复通过，阶段 B 的 Config 子契约验证（2026-11-05）

- 当前提交前 HEAD：`f11be020832766e5ba51536b8bb5b72cc5d6febc`；恢复起点 58ca263。
  普通 push，提交前确认远端无并发修改，PR #80 保持 Draft，不合并 main/其他 PR。
- 第一阶段已真实通过：运行 [41953150985](https://github.com/shawnhhh/aura-tv/actions/runs/41953150985)
  对应 f11be02，整体 success；Debug 533 项 JVM、Emulator 179 项，均 0 失败/错误/跳过。
  Debug 02:15:21Z 结束，Emulator 02:15:23Z 开始，已从 API 时间核实串行顺序。
  原始结果摘要和 artifact ID 见 validation/2026-11-05-ci-f11be02.json。
- 原 workflow_dispatch 的 gh API 请求 Forbidden，未触发；改用两个现有工作流的
  workflow_call + P0 push 单入口，needs: debug 保证成功后执行模拟器；P0 PR 不重复运行
  独立 jobs。所有关键测试、完整用例门禁、证据上传保留，actionlint 1.7.12 检查通过。
- 第一阶段全通过后才进入 B。本轮完成固定上游 ParseJob 方法级核查，见
  [parsejob-method-audit.md](parsejob-method-audit.md)。没有重做 Sardine、取消/超时/清理修复。
- 实际发现并修复：上游 VodConfig 的非空解析列表会注入 type 4 Super 默认项，当前宿主
  以前却只选择首个配置解析器。新增 ParseCatalog 接入生产 Host，实现默认/命名/flag 子契约，
  首接口 500 时可使用后续有效 HTTP 解析器。空配置不激活自动解析，不自动执行 type 2/3 JAR。
  Super 名称对照了固定提交原版默认资源；没有改首页、TMDB UI 或授权边界。
- 回归贯穿实际配置解析、模型映射、ParseJob 和两个 MockWebServer，检查错误端点回退、
  请求头、命名/flag 筛选、空配置、未调用 JAR。红灯 3 项中 2 项失败；修复后定向通过。
- 本地完整 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过：
  **536 项 JVM，0 失败/错误/跳过**，lint 和 Debug 构建成功。静态检查及 diff 检查通过。
  日志：validation/2026-11-05-parse-config-{red,targeted,local-success}.log。
- 本断点随 Config 代码提交；最终 SHA 以 git rev-parse HEAD 为准。push 后的新 HEAD 会自动
  再跑同一串行 CI，不能把 f11be02 的成功继承到新提交。当前阶段 B 仅此子契约完成本地验证，
  整体 Config/Loader/PlayerManager 仍未完成，未进入 C/D。
- 下一条明确操作：查询新 HEAD 的 actions/runs，等待 Debug 成功后 Emulator 全量结果，
  失败优先读日志修复；全成功后下一小任务是 Loader 的当前授权 ClassLoader 与固定
  BaseLoader/JarLoader 生命周期契约核对及必要回归，不重复默认解析选择修复。
- 查询 push 运行应使用 github_fetch actions/runs?head_sha=<SHA>；专用
  fetch_commit_workflow_runs 过滤 pull_request，空返回不能证明 push 没启动。
- 额度仍无准确读取接口、无不足提示；本轮仅 CI 恢复 + 一个 Config 子契约，不扩大到大型移植。
  不虚构剩余百分比或后台自动唤醒。TCL T7L Ultra 未实测，P0 未完成。

下方是此前已验证的实现记录；当前操作以上方 CI 断点为准。

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
