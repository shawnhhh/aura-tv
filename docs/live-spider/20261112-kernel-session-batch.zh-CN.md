# HERRIN P0 — 2026-11-12 新内核会话与网盘搜索批次

Branch: `codex/herrin-p0-live-playback-20261110`。
本次起点 `7162f84e`，保留 Draft PR #84，不合并 main，不修改已验收 UI。

## 代码变更（已提交）

- `e3f653fa`：Compose 播放页面的退出处理增加影视仓会话租约。
  新影视仓已接管时，旧播放页面销毁回调不再调用新会话的 stop。
  普通 NAS/CMS 播放维持旧路径。
- `0edfb2dd`：原版 FongMi `homeContent` 可选；已经得到合法授权且原版仓库载入成功后，
  只对 `UPSTREAM_CALL_FAILED` / `UPSTREAM_RESULT_ERROR` 的首页失败允许空首页继续，
  **不绕过**仓库授权、安全异常、会话失效、主动取消，仍使用原版 `SiteApi.searchContent`。
- `cb2cd922`：正在播放的原版 Player 订阅及释放行为增加源会话保护，
  防止旧状态回调更新新播放页或关闭新会话。
- 本提交：一次性验收这批宿主 Kotlin 与独立 kernel contract，
  同时运行宿主 JVM 回归；保持缺媒体依赖的整体内核入口禁用。

## 尚未完成

- 固定 FongMi TV@305dff2 所需的定制 Media3 / MPV / FFmpeg 真实合法依赖仍待来源核实，
  因而 `fongmi-kernel/:core` 和 `:adapter` 的完整 Android 构建**未验证**。
- 新内核尚未接管正式播放 APK；旧 Spider、Proxy、HLS 未从正式产物删除。
- 奶酪及玩偶哥哥《小城良方》真实出帧、60 秒播放、Seek、切集 **仍未通过**。

本批测试仅属于宿主与内核接口回归，不包含实机、模拟器或真实第三方播放；
Actions 绿色不代表 P0 完成。
