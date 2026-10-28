# HERRIN M4.18 — 实际饭太硬单仓与 FongMi 官方加载器对照

## 当前真正复现过的部分（2026-10-28）

在非电视的开发环境里，直接读取用户给出的 `http://www.饭太硬.net/tv`（IDN 实际请求为其 ASCII 域名）：

- HTTP 200，大小 **20,063 字节**；Content-Type 虽声明 `image/x-ms-bmp`，文件实际是 JPEG，末尾有 `标记**Base64(JSON)`，成功还原 **49 个 sites、6 个 lives**。这证明 HERRIN 的 JPEG 外嵌配置解析不是该截图中的故障点。
- 顶层 `spider` 是 HTTPS `.jpg;md5;…`。下载到的文件实际是含 `classes.dex` 的 1,448,574 字节 APK/DEX 容器；**实际 MD5 `b55978c4fe6d12e2fea9075f17c8a67a` 与单仓声明完全吻合**。它包含 `com.github.catvod.spider.DouDouGuard` 等类、`assets/ftyguard_v7.so`、`assets/ftyguard_v8.so`、`assets/ftyshinidie.guard`。没有执行或打包第三方受保护二进制。
- 用户 TCL T7L Ultra 第二张照片显示「Spider 子进程异常退出」，所以本次只读实测**不能证明 Java/JNI 初始化、影片目录或播放成功**。目前没有 TV 真机 tombstone/栈帧，不能断言具体崩溃指令。

## 与同一 CatVod SDK 版本 FongMi/TV@123cc08 的具体差异

参照 [官方 JarLoader.java](https://github.com/FongMi/TV/blob/123cc086159b0f13bf54bdf64bacbc259ca47c88/app/src/main/java/com/fongmi/android/tv/api/loader/JarLoader.java) 和 [官方 App.java](https://github.com/FongMi/TV/blob/123cc086159b0f13bf54bdf64bacbc259ca47c88/app/src/main/java/com/fongmi/android/tv/App.java)：

1. **HERRIN 曾主动 `System.load` JAR assets 中的 ARM Guard 库**，而官方 JarLoader 不主动这么做。主动触发 `JNI_OnLoad` 可能绕开 JAR 自身的初始化次序，且原生库崩溃无法被 Kotlin try/catch 拦截。本轮取消主动加载；保留经 MD5 核验的 ABI 原生库提取与 DEX 搜索目录配置，交给 JAR 的 Init/Spider 自行决定是否加载。
2. **HERRIN 曾把 `Init.init` 的所有异常当成致命失败**；官方 JarLoader 会记录并继续，只有 `RuntimeModule.MissingException` 这类缺少必需运行时的错误会终止。本轮对齐这一语义，严重 VM 错误仍不能吞掉。
3. **尚未对齐的依赖**：FongMi 将 `Runtime.activity()` 绑定到前台 Activity，`Spider.net` 使用具备 Cookie、User-Agent、Dialog/WebView 能力的 `JsHost`；HERRIN 出于第三方进程崩溃隔离采用 `:spider` Service，**没有本进程 Activity** 且目前使用 `Host.NONE`。受保护 Spider 若依赖这些能力，以上两处修复后仍可能失败。不能在未验证隔离安全与兼容性的情况下把未知 JAR 直接放回 UI 主进程执行。

## 新增电视可读的原生崩溃阶段标记

在插件子进程执行下载、准备 ABI、建立类加载器、`Init.init`、创建实例、`Spider.init`、读取片库、详情、播放时，保存一个不含 URL、密钥、站点参数的短阶段值。子进程因 JNI 崩溃无法回报错误时，主进程读取最近两分钟的阶段，站点页可显示「最后阶段：…」，帮助下一次 TCL 实测定位。正常完成后标记为「就绪」不误报；过期或损坏记录不展示。

测试包括阶段落盘/过期/损坏行为、可选 Init 异常与必需运行时异常的区分。CI 编译和 Android TV 模拟器结果以本提交为准。

**尚无可运行官方 FongMi TVBox + 当前受保护 ARM 插件的同一硬件对照结果**。开发环境的 API 30 x86 TV 模拟器无法运行这个 ARM-only Guard；端到端结论需要 TCL ARM 真机更新版本后验证，不得将 MD5/配置读取成功当成片源播放成功。
