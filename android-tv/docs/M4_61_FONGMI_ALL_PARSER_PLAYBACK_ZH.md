# HERRIN M4.61 — FongMi 完整播放链路兼容升级（仅播放域）

## 用户要求、变更边界

目标是消除 TVBox/FongMi 播放流程和 Herrin 之间的功能分叉。**只允许更改播放相关逻辑**，不得改主页、海报视觉、选集 UI、遥控器交互、TMDB 刮削、人物资料、分类、详情 API、收藏和影片元信息。

本次主分支未合并，独立 Draft；保留 Herrin 原生 Compose UI 和当前 Media3 播放输出。第三方运行代码的权限风险独立评估。

## 上游依据

FongMi/TV `fongmi` 分支提交 `5e191aaced194e5cac5668cc91908ecb65336222`：
- `player/parse/ParseJob.java`：`playUrl=json:`、`playUrl=parse:`，type=0 browser、1 JSON、2 JSON extension、3 MIX extension、4 aggregate；
- `ui/custom/ParseWebView.java`：网页请求拦截和媒体类型判断；
- `api/loader/JarLoader.java`：从已加载的 JAR 调用 `com.github.catvod.parser.Json<name>.parse(LinkedHashMap, String)` / `Mix<name>.parse(LinkedHashMap, String, String, String)`；
- `api/loader/BaseLoader.java`：区分 Java Spider、QuickJS、Python、Node 运行环境；
- `player/mpv/MpvPlayerEngine.java`：可选 MPV 原生引擎。

仓库中现有 GPL-3.0 CatVod SDK 保留，上游协议对应代码和来源标注 GPL-3.0；正式开源前核查完整源码和构建文件可获得性。

## 已实现的播放域改动

1. 单仓配置读取所有五种 `parses`，仅保存在播放配置内；现有旧 `jsonParsers` 保持向后兼容。
2. 独立 FongMi 播放路由器按 `json:`、`parse:`、flag、MIME、媒体后缀进行选择；不改普通片库目录/详情。
3. type0 使用后台系统浏览器运行 JS 加载解析页面，拦截确实由网页请求的 HLS/DASH/MP4 等媒体 URL，并交给 Media3。无可见界面，不覆盖遥控器焦点，不自动信任 TLS 错误，不注入 JS Bridge，不允许本地文件访问。超时和生命周期释放有界；通过 Spider 子进程独立 Chromium 数据目录。
4. type1 沿用 JSON 解析、原始 URL 拼接、完整媒体 MIME 信息及同源认证隔离。
5. type2/3 利用**用户已经授权并加载**的 Spider JAR 的对应 `Json` / `Mix` 类，按 FongMi Java ABI 调用；不下载任意新增 JAR，不做 DRM 破解或越权解密。
6. type4 聚合按本次配置的 JSON/网页解析器有界尝试；没有结果就清晰返回失败，不误报播放成功。
7. 同时接到 Spider 的选集播放以及 CMS 的后台观看页播放，原 Media3、全屏 UI 和影片资料不变。
8. `check_static.py` 只对独立解析后端和 Spider 子进程浏览器数据目录作明确例外，仍禁止其余生产模块引入网页控件。

## 验收规则

- 新增 JVM 类型路由、CMS 保持、HTTP 授权、直链不被误解析测试。
- TV 模拟器新测试 `M461BrowserPlaybackTest` 验证真实 HTML 页面发起 MP4 请求 → 后台网页解析 → Media3 原创视频真正出帧；与已有 `M459JsonParserPlaybackTest`、Spider 代理、音轨/字幕、选集等测试一起跑全量。
- 仅当最新 HEAD 的 Android Debug/JVM/Lint/APK 和 TV 模拟器同时绿色，才接受自动化结果。模拟器无法替代 TCL T7L Ultra 实机第三方网站的播放和 HDR 验收。

## 未完成的全量上游原生运行环境边界（不能虚报）

- QuickJS/Python/Node 若缺上游 native runtime/模块及对应 ABI，独立配置站点仍无法执行。它们不能仅靠 JSON 播放路由恢复；
- MPV 使用 FongMi 维护的 Media3 fork 和原生解码库，无法作为普通 Maven Media3 1.9.4 无缝复制；无真实 native 库时不得强行声称可用。
- type0 网页解析包含受网站 JS/动态鉴权/验证码影响的不确定性，不保证每个影视站点完成解析。
- 真实 TCL、饭太硬每种线路必须以授权真机可解码帧为验收标准，不能把测试用本地片源当作第三方源验证。

本次只做播放域，不做任何影片资料/刮削/UI 顺带修改；保持 Draft。

## M4.61 后续修复：运行时版本握手

固定对照 FongMi/TV `fongmi@5e191aaced` 的 `buildSrc/.../AbiApkPackaging.groovy`：Python 兼容标识更新为 `catvod-python-plugin-v3`，QuickJS 更新为 `fongmi-quickjs-ng-0.17-v12`；NodeJS 保持 `catvod-nodejs-plugin-v2`，MPV 保持 `mpv-jni-ffmpeg-shared-v1`。添加 JVM 版本握手回归。

**兼容 ID 不等于运行时已就绪**：仍必须同宿主签名构建 APK、核对 native ABI 和完整 payload 哈希，进行实际加载及播放出帧回归；不得关闭运行时签名校验。
