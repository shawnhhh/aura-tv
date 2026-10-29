# M4.37 Spider 扫码授权桥

基线：`2509da39a83bdca0b5cfc1fe2ac3a37e67b3d9da`。

根因：原有 CatVod SDK 已包含 `Dialog.show`，但 HERRIN 没有注册
`Runtime.setDialog`；`:spider` Service 的 `Runtime.activity()` 为空，不能直接显示电视 Activity。
FongMi 的 App 则注册 `SpiderDialog::show`。本实现检查了 FongMi/TV fongmi 分支
SpiderDialog 的 JSON 协议，独立实现跨进程桥，未复制其 UI 源码。

`TvBoxSpiderService` 注册 Runtime.dialog 并通过同应用 Messenger 发送
AUTH_SHOW/AUTH_ACTION/AUTH_CLOSE。当前调用号与弹窗号共同限定会话；迟到事件不能唤起旧会话。
主 Activity 根部挂载 `SpiderAuthorizationHost()`。UI 只生成二维码和固定中文提示，
不打印二维码内容，不把地址放进 accessibility semantics。授权窗口使用 SecureOn，
防止个人扫码内容进入截图、录屏、CI 截图。

支持 FongMi/CatVod 的 `qrcode`、内嵌 base64 `image`、1–3 个 `buttons`，
按钮事件 `{button,values:{}}`、原插件线程 `onAction` 回调，以及 `qrcode/image/close` 更新。
合法授权 Cookie/Token 的交换与保存仍由原插件及其平台完成，主界面不提取、不保存、不回显这些值。
回调执行通过 SDK 自带的 action queue 回到调用 Dialog.show 的插件线程，不在 UI 主线程执行插件。
非关闭按钮可检查状态、刷新二维码或注销；注销按钮动作由插件实现。
取消、Back、源切换、bridge.close、进程断开、120 秒超时均释放 pending future/二维码。
为了容纳两分钟扫码，工作调用最长150秒，子进程 watchdog160秒，主进程IPC165秒。

不支持依赖 Activity/WebView 的自建二维码、带输入表单的 Dialog、平台 DRM、未知私有授权协议，
这些路径明确失败；不能把支持 Runtime.dialog 等同全部网盘插件均已验收。
插件自有持久化凭据不由此通用桥读取或跨站复制；用户应通过其注销动作清除平台会话。

自动化新增 5 项 JVM 用例和 2 项 Android 测试。Android 用 debug-only 独立进程 fixture，
执行真实 CatVod Dialog.show、Messenger、原创 MockWebServer 授权结果、真实 DPAD_CENTER/BACK。
所有二维码与 token 为原创假数据，无外部平台、账号、Cookie。
本地 Gradle 尝试被环境网络对 jitpack.io 的403阻断，不能声称这些新增测试本地已通过；
最终结果以集成分支 GitHub Actions 为准。
