# Herrin P0 — 2026-11-12 连续开发检查点

本批在独立开发分支 `codex/herrin-p0-live-playback-20261110` 开发，起点 `b3ee2c9c`。
保持 PR #84 Draft，不合并 main，不修改已验收 UI、TMDB、NAS/WebDAV/SMB 顺序及独立直播。
继续直接复用上游 TV@305dff233cde512e2c83f71529b7c3ecfd61fefc 和已有 `fongmi-kernel` 模块，
没有重下载源码，没有用新解析器/代理/HLS 补丁替换原版。

## 本批代码提交

- `04fda8d6`：原版 `FongMiKernel.refreshDetail` 对整个当前选集做替换，保持仍有效句柄，
  淘汰上游网盘展开/源切换时不再出现的句柄；`HerrinKernelPages.projectDetail`
  原子更新选集镜像，不再允许旧详情操作被移除的线路；避免跨线路标题覆盖以及过期选集异常传播。
- `68d4c20a`：显式点击剧集时先由原控制器切换线路，仍未开放实际播放；
  然后才准入选定剧集。避免原控制器 `seamless` 历史集数动作先调用一次 Spider，
  再为用户所选集数重复调用。原有 `VodPlaybackController` 和 `SiteApi` 未重写。
- `8a06585a`：切换/重载影视仓立即取消原会话已跟踪的异步任务，
  废弃旧站点/影片/选集状态；旧 IO 调用即使仍在等待，也不能在新会话发布结果。
- 本检查点进一步将上述代码路径加入宿主轻量编译触发范围。

## 构建范围和真实状态

本批先完成代码开发，最后才触发一次轻量 Android 宿主编译，验证
`:kernel-contract:compileJava`、`:app:compileDebugKotlin`、
`:app:compileDebugJavaWithJavac`。**这不会编译独立 `fongmi-kernel/adapter`**；
其 `core` 仍缺乏与固定 TV 提交严格匹配的定制 Media3、MPV、FFmpeg 及原上游
hook/tvbus 来源/版本/许可锁定。不能以宿主 CI 通过宣称原版完整 Android 内核可编译。

实质性下一项：解决已在 `android-tv/fongmi-kernel/dependencies.json` 明确列出的
原版依赖缺口，填真实源码/二进制来源校验和、许可与对应版本，
完成原版内核 Adapter/TV 宿主完整编译；
在此之前不得强制混入现有 stock Media3 或激活新内核入口。
随后才做原旧内核唯一切换、去除旧播放执行路径及统一 P0 回归。

未做饭太硬节点、模拟器或完整 TV/ABBA 测试；《小城良方》在
奶酪、玩偶哥哥真实首帧、60 秒、Seek、重播与切集仍**没有通过证据**。
不交付验收 APK；不把绿色 Actions 任务当作真实视频播放。
