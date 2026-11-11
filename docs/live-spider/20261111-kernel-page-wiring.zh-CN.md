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

## 后续连续完成

已加入动态网盘选集/播放坐标回传到现有页面；自动切集不会被页面重组再次触发解析，
明确重播会重新取得原版播放状态。站点/仓库变化时校验请求session，旧结果不能用新站点
授权签名。Source重定向更新Vod ID时保持逻辑影片handle，按原数据更新category/remarks，
不写死剧集类型或改变TMDB规则。

原授权Activity维护真实pending request、完成/取消/销毁通知；Kernel.State新增
authorizationPending，只表示原源请求仍待处理，**不表示QR已显示、授权成功或已出帧**。
内核关闭会撤销全部待处理对话框并禁止无会话请求打开授权页。

独立构建加入原QuickJS/Node/Thunder/Python bridge工程及源码AAR构建脚本。
Python原native.lock固定python-android@4cabf1d1bce37d1fc517d939c54b8087af8f9cd3，
已通过公开commit元数据确认存在；ABI/二进制输入及未知来源项记录在evidence JSON。
仅构建原AAR，不生成APK、不改包名/签名、不以二进制存在推定对应源码合法齐全。

仍待：精确旧会话退休与Surface门禁集中检查、原媒体依赖版本/源码匹配、默认宿主小范围
编译检查。完整原版classpath尚未验证，whole源目录/清单不加入稳定构建。
