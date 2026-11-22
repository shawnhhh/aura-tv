# 2026-11-21 实机反馈续修证据

日志保留成功和失败；只规范化了行末空白，没有删除失败、断言或错误正文。完整CI Artifact和源码SHA另见主台账。开发过程中工作区变化的本地日志只作诊断，不冒充最终源码全量通过。

- `ci-82b4-debug.json`：当前生产代码807项、Samba、Lint/Debug及诊断APK绑定。
- `ci-test-only-scope.json`：5f90测试修正仅复用上述相同输入的检查，TV/API31/ABBA不复用；计数修复dc2d继续遵守同一边界。
- `ci-5f90-preflight-count.json` / `.log`：20项预检实际通过，但旧19项门禁退出；不是全量通过。
- `ci-11c3-tv-failure.log`：实际H-004预回归失败，完整TV尚未执行。
- `targeted-summary.json`：81124cad的31项专项；不是82b4完整回归。
- `adapter-source.json`：真实固定上游文件来源校验，不是内核编译或授权成功证据。
- `adapter-preflight.json` / `adapter-build.log`：依赖门禁与独立构建的实际阻塞。
- `real-warehouse.json`：真实第三方配置获取403，仅该阶段，无插件执行或首帧。
- `phone-source-binding.json` / `phone-browser/`：当前前端文件摘要和Chromium mock API回归，非真实Safari/TV端到端。

- `ci-4c7a-results.json` / `ci-4c7a-tv-summary.json`：292项实际291通过1失败；H-004间歇超时未定位，不因本批通过判定已修复。
- `ci-4c7a-api31-failure.log`：null root / 无效XML，日志完成事件不能替代有效界面观察。
- `ci-4c7a-abba.json`：四轮各6项真实模拟器数据；缓存导航回退，不是全面提速。

- `ci-7eff-results.json` / `ci-7eff-tv-summary.json`：最新全量291/292，唯一失败为继续观看未出首帧；短链修正通过。
- `ci-7eff-api31-failure.log`：有效快照品牌断言失败，后续才有12.3秒交接日志，不能改写成UI通过。

- `ci-18cbc-results.json`：仅续播三轮12/12与API31通过，非完整TV；APK不含7845后续生产修改。
- `ci-original-evidence-inspection.log` / `api31-first-data-18cbc.jpg`：GitHub内只读读取原归档；纠正7eff失败阶段为播放器建立前，原XML仍为Splash，真实录像含系统栏。
- `index-transaction-targeted.json` / `.log`：7845仅2项本地专项通过，新完整CI44968547697待结果；旧807不冒充新版本通过。

- `ci-7845-independent.json`：7845的API31与ABBA真实完成，TV仍为预检失败；不含711后续H-001修复。
- `startup-motion-compile.json` / `.log`：8be实际AndroidTest编译，未执行测试。
- `ci-8be5-attempt1-dependency-block.json` / `.log`：808项通过，但Lint解析既有依赖失败；一次同SHA失败作业重试待结果。官方HEAD200不替代Lint。

- `ci-8be5-attempt2-debug.json` / `.log`：同SHA一次失败作业重试成功，JVM808、Samba6、Lint/Debug与新APK绑定；TV/API31/ABBA另行记录。

- `ci-8be5-controlled-source-timing.json`：同源码JVM受控loopback的阶段耗时原数据，不是远端影视仓或TCL提速；candidate p95=89.834ms、verified p95=131.29ms，未与真实网络前后作比较。

- `ci-8be5-tv-summary.json` / `ci-8be5-tv-failure.log`：295项294通过1失败，唯一导入测试:55同步竞态，原断言保留；不是全量通过。
- `ci-8be5-api31.json`：同SHA冷/热/首数据断网/force-stop/配置断网通过，32实际取帧。
- `ci-8be5-abba.json`：同SHA四轮各6项，导航均值回退1228.5ms，性能未验收。
