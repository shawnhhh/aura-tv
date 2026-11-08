# 手机扫码 HTTP 影视仓与局域网快速配置专项

日期：2026-11-08。交付到原 Draft PR #84；不合并 master。

## 改动与使用

打开原「手机扫码配置」，默认仍是 HTTPS，核对电视完整证书指纹后配对。
同一窗口新增「局域网快速配置」，切换时关闭原监听并生成新的临时端口、配对码及 HTTP 二维码。
手机浏览器直接打开 HTTP 页面，点击请求后必须在电视确认。退出窗口、切换模式、电视退到后台或结束会话关闭监听；启动完成时再次核对前台生命周期，防止启动途中退出留下监听。
配对码 3 分钟有效；HTTP 服务从创建开始最多 5 分钟，请求不能续期。
仅选择已启动非回环网卡上的私有 IPv4；不使用 0.0.0.0 或公网监听。测试允许绑定回环地址。

HTTP 是明文，仅用于可信家庭局域网；配对码不能防止局域网中间人攻击。
只允许提交公开 TVBox 地址，不允许填写任何凭据。
服务端按路径和字段白名单检查，拒绝 TMDB、NAS、直播、云盘、目录管理、删除、状态读取和未知接口。
拒绝 username、password、token、headers 等额外字段；
拒绝 userinfo、fragment、所有查询字符串（不限 Token 参数名称）及明显含认证信息的路径。
不通过 HTTP 返回主源、NAS 或 TMDB 状态，只列出通过公开地址检查的附加影视源。
这不能判断任意不透明路径是否本身是私密能力链接，用户仍只能输入公开地址。

HTTPS 和 HTTP 配置服务均保持 Host、Origin、JSON Content-Type、32768 字节请求上限、
一次性电视确认、会话验证、禁止缓存与 CSP。
影视仓地址复用 TvBoxUrl.normalize(allowHttp=true)，支持 HTTP、HTTPS 和中文 IDN/Punycode，
保留 2048 字符、端口、危险 scheme、认证信息、fragment、反斜杠和控制字符检查。
主源 /sources、附加 /entries/save(module=sources) 与 /entries/batch 规则一致；批量导入原子校验及去重。
NAS 与直播仍调用原 HTTPS 校验，规则独立。

## /catalogs/save 核查结论

该接口是 HERRIN items 目录，不是 TVBox 影视仓：CatalogProfiles、AuthorizedUrls、
RemoteCatalog 读取链与电视管理均要求 HTTPS。
MainActivity 同样明确拒绝将 HTTP TVBox 结果导入 HERRIN 目录。
本次保留这一独立授权边界，HTTP 快速模式完全不开放该接口。
没有只改手机端存储、导致电视无法读取的半开放。

## 刷新与 Spider 授权

保存成功仍调用 applied("sources")，电视主线程增加 personalSourceConfigEpoch，
更新 configuredTvBoxUrl 并触发 tvBoxRequest / tvBoxLoadEpoch 读取元数据。
回调显式清除 tvBoxRunSpiderAfterApply；扫码不能授予一次性插件执行权限。
原 trustedRoot/trustedJar、指纹核验、TvBoxSpiderPolicy 和进程边界保持。
没有修改首页视图、播放器、搜索算法、NAS/云盘优先级或直播优先级。

## 自动化覆盖

- LanConfigurationTest：原 HTTPS 启动、证书信任、电视确认、四模块状态和结束；
  增加 HTTPS 页面保存 HTTP 饭太硬 IDN 地址。
- LanHttpConfigurationTest：HTTP 页面、待确认拒绝写入、IDN 保存、混合批量去重、
  附加源保存、刷新回调、敏感路径/字段/链接拒绝、信任值不变、新 JAR 拒绝、结束后端口关闭、公网通配监听拒绝。
- P0QrFailureTest：保留无 IPv4 / 二维码生成失败和重试；增加 HTTPS 切换 HTTP、退出及生命周期 ON_STOP 关闭监听。
- P0ExitAndEntryTest：从真实 TV 窗口截图解码 HTTP 二维码，经电视配对保存受控 HTTP 单仓，
  断言电视自动请求配置、显示导入成功，无需遥控器再次应用。
- LanSourceAddressTest / QuickConfigPolicyTest：URL/IDN/长度/认证信息/危险 scheme、独立 HTTPS 规则、
  HTTP 绝对会话期限不能被续期、未知接口和额外敏感字段拒绝。
- AdditionalServiceConfigsTest：混合 HTTP/HTTPS 和原子拒绝非法批次；
  原来把 HTTP 当无效地址的夹具替换为 file://，保留原子性断言。
- 完整 TV 必跑类门禁新增上述设备测试，保留全部既有播放、搜索、NAS、
  云盘、直播优先级和性能门禁，不缩减测试集。

## 浏览器表单验证

Chromium 手机尺寸窗口加载真实 quick.html / quick.js，受控本机 API 验证配对、HTTP 中文域名提交、
混合批量、附加源编辑、结束及无凭据输入框。与 Android 服务端测试独立，不代表 iPhone Safari 实机。
首轮浏览器编辑断言发现 name=id 表单控件遮蔽 form.id，导致编辑路由错误；
已改用 getAttribute("id")，保持原编辑提交断言并重跑。
可复现脚本：scripts/lan_browser_smoke.cjs（Node + playwright + Chromium，设置 CHROMIUM_PATH 可选）。
浏览器结果写入 LAN_BROWSER_REPORT 指定文件，默认临时目录。

## 验证记录

本地结构检查通过，43 个固定 FongMi 文件校验通过；播放边界 3 项通过；
完整 TV 报告门禁自身 4 项通过；quick.js 语法检查通过。
本地完整 Gradle 构建已尝试：默认缓存不可写，改用 /workspace/gradle-cache 后网络环境无法解析
services.gradle.org，且本地没有 Android SDK/模拟器，因此最终 JVM/Lint/Debug 和 TV 验收使用 GitHub Actions。

最终精确提交的 CI 链接、计数及 APK 见 PR #84 的本专项交付记录。
设备测试历史：5e11f5a 的 241 项中，240 项通过，仅模式切换测试在后台监听启动、
主线程地址尚未发布时抢先断言失败。真实截图解码 QR→配对→HTTP 保存→电视自动刷新已通过。
测试改为等待同一 5000ms 内地址真实显示，保留原地址/旧监听关闭/退出关闭断言；
后台生命周期测试也先等待可见地址，避免在服务尚未交付 UI 时触发停止。

CI 尚未完成的结果不视为通过。没有 TCL 实机或 iPhone Safari 实机，未宣称第三方仓库可用或真实影片播放成功。
