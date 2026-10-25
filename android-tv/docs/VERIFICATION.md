# M1 验证与遥控器验收

## 当前本地结果

2026-10-25：
- `python3 scripts/check_static.py`：通过。检查 XML、TV manifest、Wrapper JAR SHA-256/ZIP/主类与工程文件。
- `git diff --check`：通过。
- 根网页四个文件相对基线 7c768bb 无差异。
- `GRADLE_USER_HOME=/workspace/herrin-gradle timeout 20 ./gradlew :app:assembleDebug --offline`：失败于 Wrapper 分发启动，`java.net.SocketException: Operation not permitted`，详见 local-build.log。`--offline` 不能避免 Wrapper 在本机无 Gradle 时下载分发。
- 本机没有 Android SDK、Gradle 分发或依赖缓存；依用户要求停止本地下载，将编译、JUnit 和 lint 交由 GitHub Actions。
- 本地未运行编译/JUnit/lint，未启动模拟器，未在 TCL 真机验证，不提供虚构截图。

云端结果以 PR 的最新 `HERRIN Android Debug` 检查为准。失败时查看 job 日志及 HERRIN-test-reports。

## TCL T7L Ultra / TV 模拟器清单（待人工执行）

| 场景 | 验收步骤与期望 | 结果 |
| --- | --- | --- |
| 启动 | 冷启动显示 HERRIN，逐字消失至 H，约 2.8 秒进入首页 | 待测 |
| 跳过 | 冷启动按 OK 或 Back，立即进入首页；导航不重播动画 | 待测 |
| 减少动态 | 设置开启后重启，无启动动画/缩放过渡；系统动画为零同样生效 | 待测 |
| TV Launcher | 安装后显示 HERRIN 和极简 H，横屏全屏无触摸依赖 | 待测 |
| 首页 | 离线显示原创全屏画面、顶部导航、横向卡片；无安全区裁切 | 待测 |
| 方向与确定 | 上下往返导航/操作/卡片，左右滚动卡片，OK 打开详情；焦点始终可见 | 待测 |
| 焦点恢复 | 滚至后部卡片，开详情并返回，恢复卡片及滚动位置；跨类别检查 | 待测 |
| 分类与直播 | 电影/剧集/动漫筛选正确；直播显示未接入，不伪装可播放频道 | 待测 |
| 搜索 | 用系统 TV 键盘输入中文/英文；快捷关键词、清空、无结果均可用 | 待测 |
| 收藏 | 添加/取消收藏，我的页面正确；结束进程再启动仍保留 | 待测 |
| 空收藏回退 | 从我的打开唯一收藏，取消后返回，导航焦点可恢复 | 待测 |
| 详情播放 | 切换集数/线路后播放，显示正确选择和无真实流说明，Back 回详情 | 待测 |
| 返回 | 详情回来源、设置/搜索回浏览、分类回首页、首页 Back 交由系统 | 待测 |
| 稳定性 | 连续按键/进出后台/重启，确认无崩溃或焦点丢失 | 待测 |

M2 才接入合法影视接口、真实 FongMi/Media3 播放与直播。M1 不代表这些能力已完成。
