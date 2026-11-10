# Herrin P0 真实播放检查点（2026-11-10）

**P0 未完成。两个节点均未取得真实视频首帧，不生成验收 APK。**

代码检查点：86d3192122c153fd0c579cfbed647e0ebf220dc8，开发分支 codex/herrin-p0-live-playback-20261110；集成目标 codex/herrin-global-search-20261107，Draft PR #84。最新提交以 git/PR 为准，本文件随后作为证据提交，不能把旧 HEAD 的 CI 当作新 HEAD 通过。

## 本轮已验证的修复

正式 MainActivity 的两个选站入口仍对空首页调用 first()，启动恢复也丢弃 Ready(empty)。现改为保留已初始化的空目录站点、站点/JAR/root 授权及搜索入口；不自动换站，不把空内容判为无资源或已登录。

[c8572a1 定向运行](https://github.com/shawnhhh/aura-tv/actions/runs/42828213992)，job129871004691：相关 JVM/诊断构建通过；真实 MainActivity + 原创空首页 DEX 的两例通过（普通选择、搜索入口、重开恢复及高级入口）。该夹具没有第三方媒体，不是饭太硬播放证据。新增回归要求完整精确文案及原15秒上限，已加入完整 TV 必跑门禁。

早期两轮新测试因误用 assertTextContains 的默认完整匹配而失败；现场已显示正确的完整文案。已改用整段精确 assertTextEquals，未放宽等待或修改原 TMDB 断言。失败记录42825953351、42827289918保留。

FongMiExoPlayerFactory 仅提取已有 HTTP 工厂供定向诊断共用，保留原头、重定向、12秒设置与唯一 Exo 所有权，未替换播放引擎或修改生产请求行为。诊断先检查当前本机站点/会话健康，绝不跳过归属错误。单次 HTTP-first 模式始终为诊断，不计播放通过。

## 两个真实节点的独立结论

仓库 http://www.饭太硬.net/tv；用户授权《小城良方》（2026）多集。实时按名称识别：玩偶哥哥=玩偶/csp_WoGGGuard，奶酪=奶酪/csp_T4Guard。

| 项目 | 奶酪 | 玩偶哥哥 |
| --- | --- | --- |
| 初始化 | ARM64与ARMv7均成功 | ARM64成功，空首页后会话保留 |
| 搜索/身份 | 实际搜索并匹配2026，详情/29集/首集playerContent成功 | 普通和配置允许quick均0字符，不能判为无资源 |
| 最新真实证据 | [86d3192 ARMv7正式HTTP首读](https://github.com/shawnhhh/aura-tv/actions/runs/42829125288)，job129874183471 | [c8572a1](https://github.com/shawnhhh/aura-tv/actions/runs/42828213992)，job129871004691 |
| 设备 | Android11，armv7l，32位，正式选择armeabi-v7a；通过标准ABI安装/执行 | Android11，aarch64，64位，正式选择arm64-v8a；镜像SUPPORTED_ABIS仍以x86_64开头 |
| 阶段耗时 | 配置1588ms，初始化7535，搜索903，匹配101，详情302，选集105，解析1006，正式HTTP诊断4832 | 配置1364ms，初始化5814，搜索102 |
| HTTP/代理 | 会话READY；正式FongMi/Media3 HTTP数据源收到502。回调Integer200/HLS/ByteArrayInputStream正文是HTTPS引用；真实取引用得到403 HTML | 未到媒体阶段 |
| 失败码 | PRODUCTION_HTTP_502；上游HLS_REFERENCE_HTTP_403；BLOCKED UPSTREAM_MEDIA_ACCESS_DENIED | SOURCE_EMPTY_RESPONSE |
| 真实授权QR | 未观察到，不代表无需授权 | 未观察到，不可记授权阶段通过 |
| 视频字节/解码首帧 | 无 | 无 |
| 连续60秒/暂停恢复/Seek新帧 | 未到达 | 未到达 |
| 同集重播/下一集/回第1集/详情返回再播/换线 | 未到达 | 未到达 |
| 独立结论 | FAIL_PLAYBACK + BLOCKED，403原因仍未知 | FAIL_PLAYBACK，根因未解 |

不能记 REAL_PLAYBACK_PASS 或 AUTH_FLOW_PASS_PLAYBACK_PENDING。媒体清单引用、HTTP响应、Spider初始化、CI采集成功均不代替媒体读取和实际解码。

### 奶酪：已排查及仍不能归因的部分

- [0a0c3bd实时配置结构诊断](https://github.com/shawnhhh/aura-tv/actions/runs/42824167822) HTTP200、1173ms：根级和两个站点均无header/headers；1条规则无请求头字段。只排除漏用这些声明，不能认定IP封禁，也不能猜造UA/Referer/凭据。
- ARM64 [9406a00](https://github.com/shawnhhh/aura-tv/actions/runs/42827652105)：原正常探测MEDIA_NETWORK_ERROR，扩展探测EXTENDED_MEDIA_HTTP_ERROR；准备比较正式HTTP时已CONNECT_REFUSED。系统记录原生崩溃，安全摘要SIGILL/libc/libndk_translation。该轮未执行到正式HTTP请求，不能从它排除客户端差异。
- 随后标准ARMv7包，**先**用同一正式FongMi/Media3 HTTP工厂做单次请求：READY→本机502，上游仍403 HTML。没有先做两次OkHttp探测，没有手工改UA。这证明该失败并非只存在于OkHttp前置探测；仍未确定源端拒绝的具体原因。
- ARMv7运行结束后的安全崩溃摘要有SIGSEGV/插件或其他模块/libndk_translation；报告捕获HTTP错误时未记录退出原因，不能据此认定崩溃先于或造成403/502。保留原始固定类型摘要，不上传私有栈/媒体地址。
- 无授权绕过、插件修改、包名/签名/系统属性伪装，也未取消公网DNS、同源引用和站点/会话校验。

### 玩偶：仍未解决

正式界面空目录异常已修，但真实BaseSpiderGuard普通/quick均返回空文本。没有实际QR，不能声称需要登录、已登录、无资源或授权通过。SDK/公开异常观察不覆盖插件自带原生网络；没有可见SDK错误不等于没有网络访问。继续查公开调用/合法网盘入口，禁止猜私有API或绕过插件保护。

## 完整回归及交付门槛

旧PR应用HEAD adfbea4e49e791362f65e6ebdc022fb0e01d1d55 的完整常规CI已通过：JVM715/715，Lint/Debug/API31启动恢复遥控通过，TV257/257（原TmdbBrowseRefreshTest通过），ABBA24/24。对应runs42819505377、42819505310、42819505455。

这些旧结果不能充当本次新增UI修复的完整回归。新增修复已定向验证，准备普通快进集成并统一运行新HEAD的完整CI；以PR/运行链接的实际结论为准。APK工件继续禁发。ABBA旧软件AVD小样本的11次缓存导航退化615ms，不能声称全面提速或TCL性能。

真实播放测试的目录宿主仍为测试Activity，生产MainActivity详情返回驱动仍未补齐。当前硬门禁要求实际film-detail-list可见，不可把Back回调改成成功；见[NEXT-MAIN-UI.md](NEXT-MAIN-UI.md)。这与本轮已通过的空首页选站UI回归是不同场景。

## 续跑边界与命令

先读当前分支/HEAD/PR/最新CI及本目录JSON；不要重跑48节点导入、ARM canary基线或无变化403。没有新合法资源/请求语义/代码修复前，不重复真实媒体请求。

```sh
cd /workspace/aura-tv
git status --short --branch
git log -1 --format='%H %s'
# 在已授权且已登录的gh环境读取结束的原始日志：
gh run view 42829125288 --repo shawnhhh/aura-tv --log
gh run view 42828213992 --repo shawnhhh/aura-tv --log
```

仅有新修复时，在已配置SDK/NDK/Gradle和兼容设备的环境构建一次诊断包后复用：

```sh
cd /workspace/aura-tv
bash android-tv/scripts/build_live_arm_canary.sh
python3 android-tv/scripts/build_m467_spider_fixture.py
(cd android-tv && gradle -PherrinLiveSourceTest=true -PherrinArmTranslationTest=true -PherrinLiveAbi=arm64-v8a :app:assembleDebug :app:assembleDebugAndroidTest)
mkdir -p diagnostic-apks/arm64-v8a
cp android-tv/app/build/outputs/apk/debug/app-debug.apk diagnostic-apks/arm64-v8a/
cp android-tv/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk diagnostic-apks/arm64-v8a/
LIVE_NODE=cheese ARM_PACKAGE=true LIVE_INSTALL_ABI=arm64-v8a LIVE_PREBUILT_APKS=diagnostic-apks LIVE_METHOD_METADATA=false LIVE_REQUIRE_ACCEPTANCE=true bash android-tv/scripts/run_p0_selected_nodes.sh
# 玩偶用LIVE_NODE=wanou；不要用默认YGP。两者独立报告，不能用夹具替代。
```

本地没有Android SDK/KVM的环境不能声称执行了设备命令。正式一次16:57 UTC续跑任务已停用：当前已于此前主动恢复，避免并发覆盖；不是后台仍在执行，也不保证额度恢复。
