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
