# 全局搜索与首选影视点开发检查点

基线：2026-11-07 远端复核 #83 Draft，HEAD `1b584be982caa05f14cc6d2177e0bd1e502005a2`。
本轮增量分支 `codex/herrin-global-search-20261107`，Draft PR https://github.com/shawnhhh/aura-tv/pull/84 ，目标为 #83 的分支。没有合并 master，没有强推，没有删除任何 Draft PR。

## 已写入代码

- `96f8277`：官方 TMDB multi 在线搜索、过滤人物、movie/tv ID、分页、防抖、详情新条目入口；独立持久化手动首选与自动最近成功站点。
- `938a24f`：个人源→首选 Spider/CMS→其他 CMS/Spider 的调度测试，首选超时完成事件与取消预算。
- `954a3ce`：公开条目快照、收藏及详情恢复；新在线协议的模拟器搜索回归；首选失效/未授权提示。
- `e3cc28c`：独立有界搜索 HTTP 队列，协程取消立即取消 OkHttp Call，不让首页请求排队阻塞输入。
- `88b09ba`：搜索键使用完整 TMDB 身份，查询更换重置分页。

所有新增测试响应都明确是协议/调度夹具，不是真实 TMDB 搜索或第三方播放验证。详情无播放来源时仍显示资料。不从演示目录补搜索结果，不把标题匹配当成可播放。已有媒体字节验证器与 FongMi 核心保留。

## 0/45 的代码证据与边界

基线 streaming router 阶段为 NAS 1800ms、云盘 1800ms、CMS 2800ms、活跃 Spider 1800ms；最大累计 8200ms，外层前台只有 8000ms。后置 Java Spider 批次可能完全没有机会完成首个节点。这是代码中可复核的饥饿路径，不证明 TCL 唯一根因。

当前个人源合并为一个 1500ms 阶段，未配置源不加入；按配置输入顺序串行搜索。首选站点独立最多 3000ms，先验证再回退；其余 CMS 1800ms 有界并发，其他 Spider 串行。首选绕过健康排序与短期冷却，但不绕过 Spider JAR/root 精确授权。激活使用原进程隔离和 SpiderRequestBudget。已尝试且超时节点发布完成事件，继续游标保留未开始节点。没有 TCL 或真实授权影视仓，不能宣称 1–3s/3–6s 或实际播放达标。

## 真实联网证据

云环境报告 secrets=[]；没有 TMDB Token 或授权影视仓配置。网络为 restricted，API 域名未放行。

执行实际 GET：
`https://api.themoviedb.org/3/search/multi?language=zh-CN&query=小城良方`（发送时 URL 编码）。
结果：curl 56，`CONNECT tunnel failed, response 403`，`TMDB_HTTP=000`。这是环境代理拒绝，**没有收到 TMDB 搜索响应**，不能推断该作品有无结果。未发送任何凭证。必须放行 api.tmdb.org、api.themoviedb.org、image.tmdb.org，并通过安全环境配置提供合法 Token，才可完成线上验收。私人片源须用户已有授权，不能根据选择动作自动授权执行。

## 验证状态

- 本地静态检查通过（XML/manifest/wrapper、43 个 pinned FongMi 文件、90 个 Sardine 文件与播放边界）。
- Python 播放边界测试 3 项通过；native kit 检查器单元测试 14 项通过。后者使用故意损坏输入输出 BLOCKED，不是本 APK 解码能力验证。
- 本地 Gradle 尝试失败：默认缓存目录不可写；改 `/tmp/herrin-gradle` 后网络 socket 被沙箱拒绝。环境无 Android SDK/adb；使用 GitHub 的实际 SDK/TV 模拟器工作流执行完整验收。
- `938a24f` 的 CI 日志完成 Kotlin/Java 编译；后续推送取消该次剩余任务，不能记为整套成功。
- 最新 JVM/Lint/APK/TV 模拟器状态需按最新 HEAD 核对，不得用旧构建代替。
- 没有 TCL 真机验证；没有真实第三方媒体播放成功证据。

## 尚未完成与恢复

P0 必须先收敛：核对最新 CI，修复任何失败；补充快速输入、分页重试与失效首选回归；实际联网查询和用户授权片源媒体校验；确认继续游标和剩余阶段预算。个人源现在保持原配置顺序，尚无多源可编辑优先级 UI。

P1 尚未实现：统一个人媒体库表单及无损凭据引用迁移、多来源排序/启停、SMB2/3 适配和分段播放、后台增量文件索引。仓库仅已有 smbj 依赖，不能称为已支持 SMB。没有改动旧 NAS/云盘配置及加密凭据。

之后执行集中 JVM、Lint、Debug、TV 模拟器、搜索/详情/收藏/历史/续播、直播不变性、启动/遥控性能门禁，并交付最新 HEAD APK 与测试数字。

恢复命令：
```bash
cd /workspace/aura-tv
git status --short
git fetch origin codex/herrin-global-search-20261107 codex/herrin-startup-source-health-20261107
git switch codex/herrin-global-search-20261107
git log -8 --oneline
# 比较远端，确认无并行更新后继续；禁止 reset --hard / force push。
cd android-tv
GRADLE_USER_HOME=/tmp/herrin-gradle ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# 有 Android TV AVD 时：
GRADLE_USER_HOME=/tmp/herrin-gradle ./gradlew :app:connectedDebugAndroidTest
```

本文件是开发检查点，不能当作完整验收通过报告。
