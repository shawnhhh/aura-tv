# TCL T7L Ultra：HTTP 播放闭环复测

本文件是待执行清单，云端没有 TCL 或用户实际授权影视源验收结果。

1. 从最终 SHA 对应 GitHub Actions 的 `HERRIN-debug-apk` 构建产物下载 ZIP，解压
   `app-debug.apk`，通过电视安装器或 `adb install -r app-debug.apk` 安装；记录安装错误码。
   SHA/校验和与自动化结果见同次交付的 manifest/CI 回执，不能用旧 APK 冒充本次。
   若明确报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，先导出设置备份，再卸载旧签名包并
   安装；不同云端 Debug 签名不能保证覆盖安装，不要在没有备份时直接清除配置。
2. 只导入本人有权使用的 NAS/云盘/TVBox 配置，按现有界面明确批准需要运行的 JAR。
   不另行下载未经批准的 Spider/JS/Python。确认 NAS 优先、其次云盘、最后 TVBox。
3. 从 TMDB 详情选择一部作品，确认来源、季、集与线路；分别测试 MP4、HLS/TS。
   记录画面、实际声音、音轨、字幕；选集成功或返回 URL 不等于视频已播放。
4. 暂停/继续、D-pad 快进/快退、换集、返回详情、重新进入断点续播、退出播放器。
   展开「更多」，检查倍速、单集循环、跳过静音、音轨和字幕；选定音轨并关闭字幕后重试
   同一媒体，确认选择保留；换到另一集时不得沿用失效轨道索引。跳过静音需软件解码音频
   才可能生效，直通输出效果需设备实测。ASS/SSA 当前验证文字 cue，不代表 libass 特效。
   失败线路有其他已授权同集线路时检查自动切换和进度；无效/缺集不能跳第一集。
   全部失败保留手动换源；401/403/429、授权撤销或策略拒绝不得自动绕过。
5. 播放中改变片源配置或离开页面，确认旧结果不会启动新播放、旧来源凭据不会带到新来源。
6. 只收集宿主安全事件：

   ```sh
   adb logcat -v threadtime -s HerrinPlaybackDiag HerrinRecovery HerrinSpiderWorker HerrinPlayerBack
   ```

   记录对应 APK SHA、作品/季/集/线路（自选非敏感名称）、时间点、可见错误分类及事件。
   检查 `NEXT_AUTHORIZED_LINE`、`NEXT_AUTHORIZED_SOURCE`、`MEDIA_ROUTE_READY`、
   `PLAYBACK_INITIALIZING`、`FIRST_FRAME`、`TRACKS_CHANGED AUDIO=1`、
   `PROXY HTTP_RESPONSE STATUS=206`、`RANGE_REQUEST`、`PLAYBACK_ERROR`。
   原生代理每会话最多输出 16 次 HTTP 诊断，避免分片刷屏；音轨选中不等于已听到声音。
   不要导出完整含 URL/Cookie/Authorization 的第三方日志。

上游定制 Media3 的 ISO/光盘/MPV 分支仍未移植，普通 HTTP 验收不冒充这些能力。
