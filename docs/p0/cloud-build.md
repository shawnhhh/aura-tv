# Codex Cloud Android 验证环境

当前依赖方案更新：JitPack 网络策略没有被修改。用户授权评估等效依赖后，
现改为编译官方 Sardine **同一 v0.9** 的固定源码；来源、许可证、校验和宿主差异见
[Sardine 源码依赖](../../android-tv/vendor/sardine-android/README.herrin.md)。
项目无需 JitPack AAR 或额外本地 Maven/init script。下方 403 记录为修改前的历史证据。

2026-11-05 恢复记录；Linux amd64，仅用于当前 P0 分支的构建验证。
SDK、Gradle、用户配置都放在可写云端目录，不依赖本地 Mac，也不把 SDK/凭据提交到 Git。

## 已实际安装的官方工具

| 工具 | 版本 / 安装路径（相对 `/workspace/.cache/aura-android`） |
| --- | --- |
| JDK | 完整 Temurin 17.0.21+9，`jdk17/`；项目 Java/Kotlin target 17 |
| Gradle | 仓库 wrapper 固定 8.9；缓存 `gradle/` |
| SDK command-line tools | 23.0.0，`sdk/cmdline-tools/latest/` |
| SDK platform | Android 35 revision 2，`sdk/platforms/android-35/` |
| SDK build-tools | 35.0.0，`sdk/build-tools/35.0.0/` |
| AGP 自动请求的 build-tools | 34.0.0，`sdk/build-tools/34.0.0/` |
| SDK platform-tools | 37.0.1，`sdk/platform-tools/` |

下载来源为 Google 官方 `https://dl.google.com/android/repository/`。
使用官方 `repository2-1.xml` 的条目核对 command-line tools 压缩包：
`commandlinetools-linux-16111833_latest.zip`，SHA-1
`e025545c62a8e64c7559119566a569fb1dec5f60`，181052239 字节。
校验后解压，再使用其中的官方 SDK Manager 安装
`platforms;android-35`、`build-tools;35.0.0`、`platform-tools`。
新版 SDK Manager 报告 `--licenses` 已无须使用；安装返回成功，包版本经
`sdkmanager --list_installed` 确认。此安装授权不涉及任何外部 Spider 插件执行。

## 在同一云端环境继续验证

以下路径为环境缓存，重建执行环境后须先检查是否仍存在；不能只根据本文件声称已安装。
不写入只读 `/home/agent/.gradle` 或 `/home/agent/.android`。

```bash
export ANDROID_HOME=/workspace/.cache/aura-android/sdk
export ANDROID_USER_HOME=/workspace/.cache/aura-android/user
export GRADLE_USER_HOME=/workspace/.cache/aura-android/gradle
export JAVA_HOME=/workspace/.cache/aura-android/jdk17
export JAVA_TOOL_OPTIONS='-Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080 -Djava.util.prefs.userRoot=/workspace/.cache/aura-android/prefs -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts'
cd /workspace/aura-tv/android-tv
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain --max-workers=2
```

proxy:8080 来自本次环境 `/etc/codex/network-policy.json`。新环境应重新读取实际网络策略，
保留注入的 HTTP/HTTPS 代理和 CA 信任；不关闭 TLS 校验，不照搬凭据。
命令需使用允许网络的执行权限；代理配置不会绕过目的地主机策略。
`--offline` 仅适用于依赖缓存已完整的情况，首次构建不能据离线缺依赖就判定依赖不存在。
当前 AGP 8.7.3 自动选择 Build Tools 34.0.0；即使已经安装 35.0.0，首次构建仍会请求
34.0.0。这不是平台 API 35 缺失，也不需要为通过检查而修改生产 Gradle 版本。

环境原有 OpenJDK 21 实际只有运行时工具，缺少 `javac`/`jlink`；源码编译恢复后，
AGP 的 JdkImageTransform 因此失败。完整 JDK 来自 Adoptium 官方
`temurin17-binaries` release `jdk-17.0.21+9` 的
`OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz`，按其官方 SHA-256 文件核验：
`8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711`。
`jlink --version` 实际输出 17.0.21。使用环境现有 `/etc/ssl/certs/java/cacerts`
保留代理 CA 信任；不要使用禁用 JDK 镜像转换或 TLS 校验的参数掩盖环境缺失。

单元测试/lint/构建的 SHA、结果、下一条恢复操作见 [断点](cloud-progress.md)。
本文件仅记录构建环境，不证明 Android TV 模拟器、第三方实际片源或 TCL 真机通过。

## 本轮结果

在 `51fc0f282bc02bd05295444b4612769ad8f7187e` 上恢复构建，SDK 定位及 AGP 配置成功。
任务停在 `:app:checkDebugAarMetadata`：现有依赖
`com.github.thegrizzlylabs:sardine-android:0.9` 的 JitPack POM 请求返回 HTTP 403。
当前云端受限网络策略未包含 `jitpack.io`；独立 CONNECT 请求也被代理拒绝。
这不是单元测试失败：编译、单元测试、lint 和 APK 生成均尚未执行到。

原始命令输出保存在 [构建日志](validation/2026-11-05-cloud-build.log)。
当时的恢复所需配置为：在当前云端环境的网络白名单增加 **jitpack.io**，保留现有允许项、代理及 CA。
当前可用工具没有网络配置修改入口，因此需从云端环境配置界面处理。
当前采用用户随后授权的官方源码编译方案；未替换镜像、绕过代理、删除功能依赖或跳过失败任务。
