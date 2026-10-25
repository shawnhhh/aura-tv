# FongMi/TV 集成决策

2026-10-25 通过 GitHub 读取 FongMi/TV 的 `fongmi` 分支目录及 app/build.gradle：
- `app/src/main` 为共享逻辑；`app/src/leanback` 为电视 UI；`app/src/mobile` 为移动端 UI。
- 当前构建使用 leanback/mobile product flavors、catvod 模块及 `app/libs/*.aar` fileTree。
- 根 AGENTS.md 提醒某些版本需要非仓库 lib-*.aar。当前源码仅有 fileTree 声明不足以证明全部二进制依赖可获得。
- 当前 app/build.gradle 还读取 local.properties 签名信息，debug 也绑定 release signingConfig。不能把上游构建称为开箱可复现。

来源：https://github.com/FongMi/TV/tree/fongmi/app/src
构建：https://github.com/FongMi/TV/blob/fongmi/app/build.gradle
许可：https://github.com/FongMi/TV/blob/fongmi/LICENSE.md

M1 决策：保留 HERRIN 独立 Compose 外壳，通过 CatalogSource / PlaybackSource 适配，不迁移整套上游 UI。
迁移外壳到上游能复用更多播放控制，但会将本项目绑定到其依赖、签名和 UI 架构；独立适配更利于保持已确认设计。
目前没有复制 FongMi 代码、打包 AAR 或实现真实 FongMi 播放。

M2：先固定经审核的上游 commit 和依赖清单，核实所有 AAR 的来源、许可、ABI 与可复现性；
为合法目录实现异步 CatalogSource；实现合法播放源解析和错误/超时；在电视设备接入并验证播放器生命周期、音轨字幕、遥控和回退。
Media3 也可作为独立播放引擎选项。播放器引擎与目录解析保持分离。

FongMi/TV 使用 GPL-3.0。若复制、链接或分发构成其衍生作品的播放器，应评估并履行 GPL-3.0：兼容许可、保留版权及许可、提供对应源码和必要构建材料，并满足适用的安装信息义务。
接口隔离本身并不自动免除许可责任；引入前须决定整体分发许可。不要加入来源不明/私有 AAR、抓取器、未授权播放源或生产密钥。
