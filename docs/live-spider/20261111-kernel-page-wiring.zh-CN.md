# 正式页面与原版内核接线检查点

起点a4088e4e；固定TV305dff2继续复用。不重建旧模块，不重跑1360文件审计、原版APK、
节点、模拟器、完整TV或ABBA。下列是开发代码状态，不是编译或播放成功证据。

## Application、搜索、详情与原选集

稳定HerrinApplication保留super/CrashJournal，并初始化不分配播放器的运行时注册表。
wholeKernel独立源目录提供真正继承原KernelApplication的WholeKernelApplication，保留
CrashJournal、原Runtime/Startup/授权Dialog生命周期；**此源目录与清单尚未启用**。
只有该Application提供真实WholeKernelInstaller时才选新入口，默认版本不创建新核心。

正式TvBoxSpiderBridge按进程选定模式将browse/search/details/play转给HerrinKernelPages；
whole模式禁止进入旧service()。失败不会回退Binder/旧解析。Film与原选集只作显示投影，
原Movie/Episode句柄留在注册表；新PlaybackMedia携带明确KernelPlaybackTicket，非媒体URL。
选集准入不再记为MEDIA_BYTES_VERIFIED，旧媒体探测拒绝接受新句柄。

原版home由SiteApi.homeContent执行。详情已由OriginalVodSession→原VideoViewModel/
VodPlaybackController加载；详情页只保存原控制器待播放请求，用户点击播放才准入，
不在详情页偷偷启动playerContent。原始网盘展开选集仍由原控制器处理。
Movie/episode句柄在同一会话/同一原选择中稳定，配置变化或替换选集后失效。

## 原版播放器Surface与现有控制层

新增HerrinPlayerSession作为现有AuthorizedPlaybackPanel的传输端口，不改布局、文案、
遥控键设计。whole模式由原PlayerManager驱动Player；原PlayerView通过bind绑定Backend，
旧重试/超时/探测不接管。暂停/恢复/Seek/重播使用原核心，音轨字幕切换调用原TrackUtil/
PlayerManager，比例仍由现有viewport设置驱动原PlayerView。MPV没有Exo帧计数时保持未知，
实际firstFrame单独从原播放器事件读取，不伪造计数。

同一whole会话跨选集复用播放器宿主；变更引擎时重新绑定真实player与Surface。
unbind携带具体View身份，旧View释放不能拆掉后装的新Surface。返回详情/ON_STOP保持
原导航回调，只暂停/停止内核，不调用Application退出。个人/直播稳定旧路径保持默认。

当前待完成：动态网盘选集与播放坐标回传到页面、授权取消的状态观测、依赖源码构建适配、
精确旧会话退休门禁及最终集中语法/签名检查。完整原版classpath尚未验证，不能开启whole源集。
