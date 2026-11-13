# 2026-11-13 连续开发统一验证

代码基线4052140d；统一验证代码SHA：f7ac613380b57e1cd5d621d7af425b77756b5b8a。
P0未完成。稳定生产入口保持，完整原版core/adapter未编译接管。

| 范围 | 结果与限制 |
| --- | --- |
| git diff --check | 通过 |
| 原版依赖锁/许可 | 未通过：artifacts为空；本地未初始化固定submodule，四个AAR缺失，report INVALID；未伪造许可/校验通过 |
| 原版core/adapter Android | ENVIRONMENT_BLOCKED + DEPENDENCY_BLOCKED；原版gradlew缺失退出127，匹配定制Media3/MPV Java API依然没有真实锁定输入 |
| contract Java17 | ECJ3.42.0编译全部main/test Java通过；既有Cutover检查通过；新增Dispatch检查通过，含排队/委托取消、拒绝、LinkageError、正常完成 |
| 本地宿主Debug | 默认Gradle目录不可写；改用/tmp后沙箱网络SocketException；未完成Android编译 |
| CI宿主 Kotlin/Java/JVM/Lint/Debug | run43355940615，精确代码SHA f7ac6133；结果待本批结束后写入 |
| TV模拟器、生命周期/焦点、首页/TMDB/NAS/直播设备回归、ABBA | 本地无SDK/adb/emulator/KVM，未执行；没有沿用旧SHA通过结果 |
| 奶酪 | 本批ENVIRONMENT_BLOCKED，未请求来源，未出帧/60秒/Seek/重播/第二集/详情返回，不复测已知403 |
| 玩偶哥哥 | 本批ENVIRONMENT_BLOCKED，未请求来源，未观察QR或授权，无播放通过；不推断AUTH_BLOCKED |

## 真实代码与交接

- 1a488c55：原版选择前取消旧任务并临时关闭执行许可；修复host.replay递归；
  解绑backend及普通视图，错误码保留到snapshot。
- dd14d516：Gateway委托纯Java KernelDispatch；UI取消/主线程拒绝/原版链接错误
  明确终止请求，无新增播放器/worker/解析路径；新增契约验证。
- b8321b04：非流式来源查找补NAS→云盘→所选影视点→其他严格顺序，保留身份匹配；
  两个JVM回归覆盖四种命中停止位置与所选验证失败继续。
- f7ac6133：统一宿主CI最后触发；不上传验收APK。

A只定向读取TV305dff2构建定义、版本目录、VodPlaybackController、VideoViewModel、
PlayerManager，未下载完整TV源码或重新审计1360文件；既有staging及校验器未修改。
原版构建仍引用本地app/libs AAR，版本目录没有确立可用媒体发布坐标。
没有新增有充分对应源码/许可/ABI证据的真实依赖。两个已拒绝候选没有复验。

B原版仍唯一VideoViewModel→VodPlaybackController→PlayerManager.parse路线。
C既有可选宿主页面/PlayerView接线准备继续保留，Gateway故障处理已修，未启用生产。
D本批仅补来源优先级一致性，未改全量TMDB搜索、用户授权范围或NAS设置界面。
E未达切换前提，Application未正式切换，旧实现删除0；没有宣称全功能移植完成。
首页/海报/导航/直播代码未修改；未执行设备回归不能推断通过。

GitHub连接器核实PR84 Draft/open/unmerged；实际head
08f6640f8f3db9323fe0e08ac295c8aaa69ffaa7，分支codex/herrin-global-search-20261107。
本轮代码仅推指定独立分支，不同步PR旧分支、不force push、不合并main/master。

下一起点见P0-CONTINUE.md最新首段；优先取得真正匹配、合法原始构建输入，
再完整编译原版Android与宿主。不能以本轮contract/稳定宿主结果替代完整core/adapter。
