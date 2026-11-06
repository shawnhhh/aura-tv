# P0 Cloud 断点

## 当前：67 真机 Top250 浏览闪退专项 / 68（2026-11-06）

- 用户新确认：新版67，在Top250里浏览容易闪退；不是旧65/66的问题。尚无TCL FATAL/APP_EXIT_REASON，不能认定唯一根因。
- 基线HEAD543bf86246869b46dd10e1e4940d0a5f2213fae8，CI42156486603完整通过：569 JVM/202 TV、0失败/跳过、无主进程FATAL；PR80仍Draft。
- 实际67代码定位：海报Up统一请求play/details，而Top250/我的无这些按钮；仅有历史的源目录空态也无Hero按钮。
- 第二缺口：Top250保留原始Film条目，却用ShowCatalog合并key；原创两条同系列/不同季记录能生成相同LazyRow key。Top250应按TMDB Film ID保留独立排行身份。
- 回归先红：从实际调用点抽出原行为，TvFocusRoutesTest 8项中4失败（3焦点/1 key），日志/workspace/.cache/aura-android/p0-68-focus-keys-red.log，失败XML/workspace/artifacts/p0-68-focus-red.xml。
- 修复：Top250 Up→自身导航、我的Up→来源管理、隐藏Hero→当前导航；Top250采用原Film ID，其他合并栏目不变。列表key/路由使用当前渲染快照，换栏目/离页取消旧跨栏焦点任务。
- 修复后8项定向通过；新增完整250条原创缓存（含可产生相同分组key的两条电影），首/中/尾方向键及Top250↔动漫回归；保留全部播放测试。未修改原图质量、播放核心、来源优先级或授权。
- 图片路径追加实际证据：已核查当前AndroidX animation 1.7.6 AAR字节码，Crossfade仅在currentState==targetState时清理旧状态，连续打断会累积旧内容。字节码记录/workspace/.cache/aura-android/p0-68-crossfade-bytecode.txt。
- Hero改用最多当前/上一张两层的原图过渡，同一层换角色不重启图片任务，淘汰旧层即取消其加载；原图URL、解码目标、缓存容量及180ms淡化不降级。添加可控动画时钟的旧机制对照/两层上限/减少动态回归，不主动制造OOM。
- 首轮68提交72b3ffd91031af72c0eebfe68d68f77916087b35，CI42168145238 Debug成功；205 TV中204过，仅MediaDecoderTest暂停时钟断言失败，Top250与Hero新回归全部通过、无主进程FATAL。真实报告/workspace/artifacts/ci-72b3ffd.json，未判绿/交付。
- 排查：独立PlaybackTestActivity不加载Top/Hero，生产pause链路未变；原测试将应用线程playWhenReady掩码当成内部线程已处理。补PlayerMessage回到应用Looper的确认及两连续窗口总漂移<100ms，保持最终250ms采样/100ms容差及所有播放/释放断言；增加纯数字样本日志，未改生产暂停。
- 574项JVM已通过，静态安全/固定上游通过；新Hero及设备测试编译通过。设备回归尚待CI，不把框架保留风险当作已证实的TCL OOM。
- 版本0.4.64-alpha（68），本地574 JVM、lint、Debug/测试APK通过，最终设备测试代码重新编译通过。下一步：提交、push单次串行Debug→Emulator，下载并核验该SHA的真实APK。
- 不能把这两处可复现缺口直接宣布为TCL全部闪退根因；图片OOM等需OS退出分类/堆栈证明。准确Codex额度/恢复时间不可查询，不虚构；无自动唤醒承诺。

## 当前：11项真机体验集成 / 67（2026-11-06）

- 恢复基线/远端 HEAD：b01a4698a7e766772f9e688e5188af15fd9398c2；CI42051442821成功，PR80 Draft。工作区为本轮单代理增量，未合并/未改播放核心。
- 已接线：所有详情线路显式下行到当前集，离屏区域先挂载再聚焦；选集弹窗定位当前项。
  导航白焦点/深灰选中三态及120ms缩放；Back按物理键去重并恢复原页面/栏目/焦点身份。
- TMDB：配置决定首页模式，不随异步结果闪切来源目录；首页/Top250元数据页持久缓存，6h TTL、同页去重、离线保留、配置/语言分区；只存显示字段。
- Hero：栏目独立有效影片选择，首次取对应推荐首项；背景与文字同一身份；TMDB backdrop优先original、同片w1280预览升级，复用原缓存并按屏幕采样、单路解码。
- 最近播放：原PlaybackHistory增加公开元数据索引，不再把来源目录当历史；重启可读、逻辑季集映射回当前授权选集，凭据/签名媒体URL不入库。
- 稳定性：保留原Spider隔离/释放机制；取消旧TMDB页面结果发布；系统退出诊断只输出原因/内存计数。
  尚无用户TCL FATAL堆栈，不能认定已修复全部偶发闪退。
- 验证：新增5项JVM通过；全量569 JVM已通过（0失败/跳过）；静态/固定上游/许可证/播放边界通过。
  首轮lint发现produceState检测问题，已改可取消LaunchedEffect；刷新回归发现失效时间戳问题，已修生产缓存判断。最终全量569 JVM、lint（0错误/30警告）、Debug/测试APK通过；最后接线的Kotlin/测试APK/569 JVM/Debug重新验证成功；即将提交；后续只push集成提交并查同SHA Debug→Emulator，不继承旧CI结果。
- e5c7bcb304e39d2ec62c2647368f4479204448bf已push；CI42153391344 Debug成功，TV201项中197过/4失败、0主进程FATAL；失败日志保留于/workspace/artifacts/ci-e5c7bcb.json，不标记通过。
- 首轮4失败已定位并修生产：取消吞第二次有效Back的350ms全局防抖，改用物理键去重/过期播放器回调拒绝；实际来源推荐与Hero同序；Top250补齐影片Hero信息；导航禁用默认涟漪叠色。已补loaded Top250 Down及My真实内容焦点路由，避免指向不存在的play按钮。
- 收尾增量：每栏目保留纵向滚动；最近播放卡片直接经当前授权来源续播，原集缺失留详情；空TMDB目录仍显示历史。新增MainActivity→原生NAS fixture→播放器→原历史卡片回归；编译与569 JVM通过，待最终TV执行。导航关闭默认涟漪叠色，明确三态色值。
- 尝试用云端gh取消被后续补丁替代的旧CI返回Forbidden，未绕过/重试；旧运行已经结束（failure），接着push包含修复的新提交，保持资源串行。
- 版本目标0.4.63-alpha（67）。旧历史在授权选集可用时一次迁入；明确刷新只过期而不删除最后有效页面。待集中CI/真实APK核验。
- 基线模拟器焦点P95：密集134.46ms，横向77.53ms，纵向40.15ms；非TCL/非像素显示延迟。
  本次增加模拟器焦点P50日志；前后对比放最终CI收据。尚无真机P50/P95、内存峰值和重组实测。
- 65/66已交付CI Debug包签名不同；67交付必须再核验签名，不能保证覆盖升级或建议盲目卸载（会丢应用数据）。稳定发行签名沿用现有签名工作流，缺旧私钥不能伪造兼容升级。
- 本集成提交即断点HEAD，确切SHA以git log -1与远端PR核对；CI/下载APK的最终SHA收据写入/workspace/artifacts/manifest-<SHA7>.json，避免为补CI状态制造另一个未经验证HEAD。
- 无官方准确额度/恢复时间接口，不虚构；无自动唤醒承诺。下一条：完成收尾后统一验证并交付67。

## 当前：播放体验批量集成 / 66（2026-11-05）

- 基线 cb1a3ea8c38b16c35d2b5738ee58f18b8e015e92，PR80 Draft，CI42028728648成功。
  用户已明确确认TCL安装65后普通HTTP影片播放成功；不外推其他格式/来源/新交互。
- 1选集：复用SeasonEpisodes，按明确季集找唯一相邻集；结束回调一次自动续集，遥控器
  MEDIA_NEXT/PREVIOUS与D-pad按钮换集；修改原episode/line状态重新进入原解析链。
  缺集/模糊标签/季边界不猜测跳集，保留原选集与返回详情。
- 2历史：原PlaybackHistory增加写入ticket与完播标记；单播放器仅当前ticket可写，
  拒绝旧实例覆盖；scope/集/线路快照固定，重启继续用原历史存储，不建第二数据库。
- 3找源：复用既有NAS→云盘→TVBox与有界候选；补齐HTTP_401/403/429、代理会话拒绝码
  分类，认证/策略拒绝不自动换源。来源身份、季集匹配、Headers边界继续使用已有机制。
- 4遥控：消费者Back先收控制条再退出；增加上一集/下一集按键与按钮。debug传统诊断
  模式保留原单Back测试语义，新增consumerControls回归实际生产返回层级。
- 5轨道：只持久化用户语言/字幕关闭意图，再进入/换集重新匹配当前轨道，不存格式ID、
  标签或凭据。无可靠语言不猜选；不兼容外挂字幕跳过，视频继续。无双字幕/libass特效。
- 6稳定：旧结束回调不能换新集；旧进度不能覆盖新实例/已完播，原释放/取消/代理恢复
  保留；新增设备历史、偏好及真实结束/Back回归，保留MP4/HLS/TS及65主线程门禁。
- 7清理：静态门禁核查仍只有FongMiExoPlayerFactory创建Exo，未发现应凭猜测删除的
  独立NAS/云盘代码；native PlayerView seek也委托manager，无新增解析/代理路径。
- 8性能：历史读取按播放身份remember；结束只派发一次；沿用已授权loader复用与有限
  重试，不引入有效URL全局缓存或重复prepare抑制，避免破坏显式重试。
- 版本66 / 0.4.62-alpha。首轮编译/564 JVM/静态/lint/Debug/测试APK通过；最终收尾后
  正在统一验证。提交后查对应HEAD串行Debug→Emulator并下载核验实际APK，不继承旧SHA。
- 待验收：TCL连续多集/手动与自动换源/重启续播/偏好/遥控器；跨季末自动跨季保守停止。
  定制Media3/MPV/ISO/双字幕/音画偏移/libass等能力仍缺匹配依赖，本轮不开发。
- 无准确官方额度/恢复时间接口，不虚构。无多代理/多worktree/合并/首页展示修改。

## 当前：TCL 64 无法播放 / 主线程健康探测修复（2026-11-05）

- 基线/远端 819f77ce37d8236cde82556e649e7bf8aef5d98f，PR #80 Draft，CI42018767532
  success（557 JVM/193 TV）。用户真机报告 SOURCE_PROXY_IO_FAILURE，不能继承模拟器成功。
- 确认生产 Main LaunchedEffect→bridge.play→同步 /health HTTP，在 Binder 返回后仍在Main。
  NetworkOnMainThreadException 被吞成 IO_FAILURE 并误重建；此前端到端从测试线程调用漏检。
  真机原始异常尚无日志，不能断言所有 TCL IO_FAILURE 都是同一类别。
- 65 / 0.4.61-alpha：两次探测切IO，返回和重建前校验来源epoch/generation；有限安全异常码
  MAIN_THREAD/CONNECT/SOCKET/POLICY/TLS/STREAM/UNKNOWN；策略/TLS/编程问题不重建或自动换源。
  UI 标明健康检查失败，worker 记录 playerContent已返回/是否为空，client记录健康结果。
- 定向JVM/设备测试编译已过；新增正常监听+Main I/O失败、Main发起原创DEX→真实出帧/音频、
  杀掉隔离进程后合法恢复与新token、健康成功但媒体502。上游逐链路差异见 tcl-proxy-health.md。
- 本地集中验证已通过：561 JVM零失败/错误/跳过、lint 0错误/30警告、Debug和测试APK；
  提交后只触发该HEAD一次串行Debug→Emulator，
  读实际结果并下载65 APK核验后交付。最终回执 /workspace/artifacts/ci-<SHA前7位>.json。
- 不改首页/TMDB展示/来源顺序、不扩授权、不执行第三方插件、不合并、不开发MPV/ISO。
  无准确额度/恢复时间接口，无限额提示；TCL原片源出帧/声音仍需安装本次APK复测。

## 当前：设备交付、统一控制与字幕集成（2026-11-05）

- 恢复基线/远端/PR HEAD `68f9fcb6ceac4b46f01927e7fca981d0fa165d55`，工作区干净，
  PR #80 Draft；CI 42008276480 success，不重跑旧 SHA 的验证。
- 已下载该 run 的 artifact 13406356779，实际 CI APK 为 tv.herrin.app / 63 / 0.4.59-alpha，
  SHA256 `d03a1fd39c1252f2acc4f075e61ac726bbef9c165e5f1d84bb42b0afdc615202`。
  与本地 APK 字节不同，分别标识，交付回执 /workspace/artifacts/delivery-68f9fcb.json。
- 本批真实代码：PlayerManager 接管倍速/临时倍速/相对 seek/音轨/字幕，接通单集循环、
  跳过静音、replay；音轨/字幕选择按媒体 key 保存，重试和 manifest 改变后恢复，
  换 key/close 清理；音轨 ID/顺序改变按语言标签匹配，不用错误 ordinal 替代。
- SpiderVodCodec→Binder→MediaItemFactory 接通 SRT/ASS/SSA/TTML/VTT MIME 与 Sub.rawFlag，
  ParseJob 宿主成功回调保留原授权字幕；没有修改已通过的 ParseJob 取消/超时实现。
- 播放初始化/首帧/音轨/释放诊断只输出固定事件与数值；原生 Proxy 最多16次/实例的
  HTTP状态/Range诊断，不传URL/凭据/异常消息。安装和日志见 tcl-http-validation.md。
- 版本64 / 0.4.60-alpha；本批定向/静态/最终557 JVM（零失败/错误/跳过）、
  lint（0错误/30警告）及Debug/测试APK构建均通过。提交后必须核对该 HEAD 的
  Debug→Emulator；最终 SHA、APK真实校验值与CI结论保存在 /workspace/artifacts/ 回执。
- 固定 TV app/libs 实际仅 hook/tvbus AAR；当前没有匹配定制Media3/MPV/ISO的Java+JNI
  依赖包，不创建stub、不改普通Exo冒充。双字幕/音画偏移/libass特效仍未提供。
  未运行外部插件；无TCL/真实第三方授权片源验收，不宣称P0完成。
- 无官方准确额度百分比/恢复时间接口、无不足提示；不承诺后台自醒。单代理/单工作树。
- 下一操作：统一验证成功后提交/push本批，检查准确HEAD串行CI并下载新APK核验包名、
  版本及SHA256；失败先修真实原因。成功后下一能力为匹配定制Media3/native依赖接入。

## 当前：真实 HTTP 闭环、同集自动恢复与交付（2026-11-05）

- 基线 `b87a8a2cc8942ea686bd6779ffcab37c48ea9db7`，CI 41996515815 success（547 JVM/187 TV）。
  远端及 PR #80 已核实，Draft；单代理/单工作树，不合并，不改首页/TMDB 展示。
- 实际修复：TMDB 展示 Film 曾丢失 Spider 的原始 ID/签名；路由现在保存原始来源 Film，
  details/playerContent 用该对象，展示继续使用 TMDB；代理/ParseJob/核心不重复移植。
- 实际新增：同集失败先按质量标签尝试其他已授权线路，最多 6 条/来源、12 条总量；
  最多自动查找另外 2 个来源，保留 NAS→云盘→TVBox、正常批准/Root/JAR 校验与手动换源。
  认证/策略拒绝不自动换源；仅可靠季/集映射才跨来源，未知标签保留同来源换线及手动入口。
  换线/换源带播放位置，恢复位置只用一次；新来源拥有独立重试预算。
- 来源结果绑定影片/集数/线路/重试/来源 ID，配置变化或离开播放取消连续恢复；旧回调不复用。
  静态门禁确认只有一个 Exo Builder，UI 不绕过 adapter 执行 prepare/release。
- 新增真实原始 DEX→TMDB 路由→代理→Media3 视频/音频缓冲设备回归，原 MP4/HLS/TS/
  音轨/遥控器门禁全部保留；未下载或执行第三方插件。本地定向已过，553 JVM 全量无失败/
  错误/跳过；统一 lint/Debug/测试 APK 已全部通过（553 JVM、lint 0 error），
  本次阶段提交后检查该 SHA 的 Debug→Emulator，不能继承基线结果。
- TCL 操作/日志见 tcl-http-validation.md；无当前真机与实际授权影视源证据，不能宣称第三方
  内容成功或 P0 完成。定制 Media3/MPV/ISO 的缺口保持 player-core-integration.md 所列范围。
- 无可靠额度接口/恢复时间，无不足提示；不虚构，不保证后台定时续跑。


## 当前：快速集成 B+C 生产接线（2026-11-05）

- 模式已按用户新指令切换：连续集成、阶段性提交、一次串行 CI；不再逐小项全测和停止。
- 已核实基线 `ca038f80cdcd96618744fabcbd17d05fda402938`，CI 41982527946 success，PR #80 Draft。
- 本批生产代码：Film/线路 HMAC 来源归属及 IPC；按 JAR+批准会话复用 ClassLoader；
  Config→Spider→playerContent 带同一批准凭据；同 JAR 的 Init 缓存静态代理地址受控重绑定。
- 已接唯一生产播放器：AuthorizedPlaybackPanel → HerrinPlaybackAdapter → PlayerManager
  → MediaItemFactory → 现有真实 ExoMediaSourceFactory/Media3；准备、重试、换线、关闭已接管。
  旧 Java 媒体会话委托同一核心，移除第二处 Exo Builder；Spider 故障直链回退移入 test。
- 首页/TMDB UI、NAS→云盘→TVBox 顺序未改；未重做已验收生命周期，未执行外部插件。
- 阶段提交 `d93074e1ae182513f4f5303227b7807c7f35a3d8` 已推送：Debug success；
  CI 41992326073 的 TV 187 项中 186 通过，仅设置备份测试的滚动查询触发 SnapshotStateObserver
  跨线程异常。新增 Loader/播放器及媒体用例全通过。详见 validation/2026-11-05-integration-ci-d93074e.json。
- 后续修复测试先等列表/返回焦点的实际语义状态，不改 UI/不跳过；播放器 ID 改用绑定完整
  VOD/选集/线路的摘要，消除旧 hashCode 依赖。定向、静态、547 JVM（无失败/错误/跳过）、
  lint、Debug/测试 APK 均通过；提交后核对最终 SHA 串行 CI，不能把前一 SHA 当完整成功。
- 实际移植范围及依赖差异见 player-core-integration.md；不把未提供的定制 Media3/MPV
  用 stub 替代。TCL/外部已授权真实源仍未实测，P0 未完成。
- 无可靠额度接口或恢复时间；无不足提示，不虚构百分比，不承诺定时自醒。断点保持简短。


## 当前：Worker 关闭后晚到 init 的生产修复（2026-11-05）

- 实现前 HEAD：`c38cc7c84bacd5e230338ac9e89257702614025b`；恢复基线 45e64c1 的
  Debug/Emulator 已全通过（541 JVM / 181 TV）。PR #80 保持 Draft，无合并、无其他工作树。
- c38cc7c 是先提交的真实 DEX 复现：串行 CI
  [41979149789](https://github.com/shawnhhh/aura-tv/actions/runs/41979149789) Debug success，
  TV 185 项中仅新增四项失败、0 错误/跳过；原 181 项通过。四条生产失败是关闭后 bootstrap
  仍成功、取消后 active 残留、旧 init 成功覆盖新站点、关闭后 init 仍返回 Ready。没有 fixture
  超时或装载失败。见 validation/2026-11-05-worker-dex-red.json，不删除这些测试求绿。
- 实质修改 TvBoxSpiderWorker：在派发前捕获资源 epoch；保留同有效 sessionKey 复用；
  原子分离/失效、初始化代理归属、bootstrap/init/publish/config/home 检查；close 先清状态，
  再在 monitor 外清 proxy/Spider/HTTP/runtime。晚到实例不能发布 active/loader/proxy；旧
  清理不能清新会话 CatVodProxy。重复 close 不重复 destroy，ClassLoader 移除宿主引用，
  没有虚构 DEX 卸载 API。与固定上游 checkOpen/cacheSpider 对照见 loader-lifecycle-audit.md。
- 未重做来源意图撤销、ParseJob、默认 Super、Sardine 或 CI 恢复。worker 资源 epoch 独立
  于 bridge source/transport；原批准描述控制合法重建，未放宽 mayRun、HTTPS/MD5/DEX
  校验或外部运行时授权。没有改首页/TMDB UI，没有旧 do= 补丁，保留 GPL/原作者修改记录。
- 四项原创 DEX 设备回归使用 CountDownLatch 精确关卡，无 Thread.sleep 或时间竞争；
  覆盖 bootstrap/init/取消/旧任务返回新站点、引用/端口释放、重复关闭、重新应用及复用。
  旧端口断言在新 listener 创建前执行，避免操作系统合法端口复用误报。未执行第三方插件。
- 本地按顺序定向 → 静态（含门禁检查器4项）→ JVM全量 → lint → Debug/测试APK 编译通过：
  **541 JVM、0 失败/错误/跳过**，lint 0 error。最后仅设备断言顺序调整后补跑 lint/APK 编译。
  证据：validation/2026-11-05-worker-{targeted,static,jvm,lint,debug}.log。
- 此断点随生产代码提交；完整最终 SHA 用 git rev-parse HEAD 核对。本机没有 Emulator/KVM，
  实际 DEX 绿灯必须等修复 SHA 的 P0 Serial Verification：先 Debug success，再 Emulator
  的四个新用例/完整媒体音轨遥控器门禁。不能继承旧提交 success 或把 JVM 当设备验收。
  SHA 专属最终 CI/产物回执保存在 /workspace/artifacts/ci-<SHA前7位>.json 和 manifest。
- 下一条操作：push 后按最新 SHA 查询 actions/runs，读真实结论；失败优先修复。通过后
  下一小任务为 **B1 Film 详情与 playerContent 的完整来源归属**，再按 B2/B3/B4/B5 顺序。
  此修复仅对应已复现的 worker 晚到 init 契约，不宣称 Loader/阶段 B/P0 全部完成。
- 官方准确额度接口不可用、无不足提示；只按有边界小任务推进，不虚构额度或恢复时间。
  未配置后台定时续跑，不承诺会自行唤醒。TCL T7L Ultra 未实测。


## 当前：Worker 晚到 init 确定性复现（2026-11-05，修复前）

- 恢复 HEAD：`45e64c1e6ef4135af2a4738bdcd9f5562bfe083a`，工作区干净、PR #80 Draft。
  此提交串行 CI [41963240319](https://github.com/shawnhhh/aura-tv/actions/runs/41963240319)
  已全 success：541 JVM / 181 TV，0 失败/错误/跳过；Debug 03:47:25Z 完成后 Emulator
  03:47:27Z 开始。两项来源撤销 Binder 用例真实通过。回执在 validation/2026-11-05-ci-45e64c1.json。
- 继续同一 Loader 专项，未重复来源意图修复。实际 worker 的 JAR Init/Spider.init 返回后
  仍无关闭检查，直接写 active/loader/proxy；固定 SpiderLoader.checkOpen/cacheSpider 则检查关闭并退休晚到实例。
- 已加仓库原创 DEX fixture 的 bootstrap/init CountDownLatch 关卡和计数；没有外部插件、
  Thread.sleep 或竞态轮询。新增四项实际 worker 设备回归：关闭后晚到 init、bootstrap
  结束禁止继续构造、忽略中断的取消、旧 init 返回不能覆盖新授权站点/清新代理。
  还检查只销毁一次、清空 loader/HTTP/实例引用、代理端口关闭、合法重新应用和同会话复用。
- 生产代码尚未修改。当前云端无 Emulator 或 /dev/kvm，真实 DEX 红灯只能在现有 GitHub
  串行 Emulator 执行；本提交是可复现回归检查点，不声称修复或设备测试通过。
  本地定向/静态/JVM 全量(541)/lint/Debug 与测试 APK 编译通过，详见 worker-red-*.log。
- 下一条操作：push 此复现提交，查询该 SHA 的串行 CI，Debug 成功后读 Emulator 的四项
  失败证据；确认是生产生命周期失败后，只修该缺口，再完整顺序验证及提交。不得删测求绿。
- 官方准确额度接口不可用，未收到不足提示；本轮只完成 worker 生命周期小任务。不虚构
  百分比或恢复时间，不承诺自动后台唤醒。阶段 B/P0 均未完成，TCL 未实测。


## 当前：Loader 来源意图与晚到回调子契约（2026-11-05）

- 恢复及提交前核实的 HEAD：`2cef56b629b86952ce7abddf2bcf5042ce5cc096`。
  PR #80 Draft、未合并，远端无外部更新，工作区起点干净。基线串行 CI 41957142681 success，
  536 JVM / 179 TV，0 失败/错误/跳过；回执已补入 validation/2026-11-05-ci-2cef56b.json。
- 已逐项读取固定 FongMi 305dff2 的 BaseLoader/JarLoader/SpiderLoader 与当前
  TvBoxSpiderWorker、bridge、JarCache、授权和 IPC 路径。详见 [Loader 核查](loader-lifecycle-audit.md)。
- 本轮只修复一个实际缺口：初次 BROWSE 无条件写 lastBrowse，关闭后晚到成功可重新发布
  失效来源；关闭前排队的应用请求也可在关闭后绑定/发送，重新触发加载。
- 实质修改：SpiderSourceSession 原子 epoch/发布/撤销；bridge 在首次挂起前捕获意图，
  Mutex 等待后、绑定前后、发送及结果发布检查同一意图；transport generation 独立保留。
  close 立即撤销来源，再执行主线程解绑；不阻止已批准来源的正常运输重建，旧请求不能
  自动取得新 epoch。没有改已验收 UI、Sardine、ParseJob 修复、默认 Super 或旧 do= 路径。
- 回归先红后绿：5 项原创来源状态 JVM 测试中原先 2 项失败（晚到发布、撤销前排队）。
  修复后 Loader/现有兼容定向测试通过。新增两项真实 bridge + 原创 Binder fixture 设备
  回归，分别控制第一次主线程派发前、Binder success 后的关闭窗口；断言拒绝、无重绑/重放、
  新显式站点成功、重复 close 只解绑一次。fixture 不加载插件，新类加入 TV 必跑门禁。
- 本地按顺序完成：定向 → 静态（含 4 项完整套件报告器回归）→ JVM 全量 → lint →
  Debug APK / instrumentation APK 编译，均 success。JVM **541 项、0 失败/错误/跳过**。
  最后仅为设备 fixture 的失败清理完善 dispatcher 释放后，重跑 lint 和 APK 编译通过；
  生产/JVM 输入未再变化。原本体取消/资源/JSON ABI 回归仍保留，没有删测或跳过。
- 证据：validation/2026-11-05-loader-{session-red,targeted,static,jvm,lint,debug}.log。
  所有源码修改、GPL 说明和此断点随同一实现提交保存；最终 SHA 用 git rev-parse HEAD 核对。
- push 后下一条操作：按新 HEAD 查询 P0 Serial Verification，等 Debug success 后再检查
  Emulator；核实 2 个新设备用例和完整媒体门禁。不能继承 2cef56b 的远端成功。
  最终 CI/产物回执保存在云端 artifacts 的 SHA 专属 JSON，恢复时也应重新查询远端结果。
- 下一项尚未闭合：先用原创 DEX fixture 延迟 init 复现 worker.close 后的 active/loader/
  临时 proxy 晚到发布，再补与上游 checkOpen 等价的 worker 生命周期边界。之后核查 Film
  详情/playerContent 的完整来源归属；本轮未宣称 Loader/阶段 B 全部完成，不进入 C/D。
- 额度官方接口仍不可用、无不足提示；不虚构数值，不承诺后台唤醒。本轮按一个边界明确的
  Loader 子契约执行，后续只在验证完成且额度可用时推进。TCL 未实测，P0 未完成。

以下是此前任务的历史断点，恢复操作以上方最新条目为准。

## 当前：CI 恢复通过，阶段 B 的 Config 子契约验证（2026-11-05）

- 当前提交前 HEAD：`f11be020832766e5ba51536b8bb5b72cc5d6febc`；恢复起点 58ca263。
  普通 push，提交前确认远端无并发修改，PR #80 保持 Draft，不合并 main/其他 PR。
- 第一阶段已真实通过：运行 [41953150985](https://github.com/shawnhhh/aura-tv/actions/runs/41953150985)
  对应 f11be02，整体 success；Debug 533 项 JVM、Emulator 179 项，均 0 失败/错误/跳过。
  Debug 02:15:21Z 结束，Emulator 02:15:23Z 开始，已从 API 时间核实串行顺序。
  原始结果摘要和 artifact ID 见 validation/2026-11-05-ci-f11be02.json。
- 原 workflow_dispatch 的 gh API 请求 Forbidden，未触发；改用两个现有工作流的
  workflow_call + P0 push 单入口，needs: debug 保证成功后执行模拟器；P0 PR 不重复运行
  独立 jobs。所有关键测试、完整用例门禁、证据上传保留，actionlint 1.7.12 检查通过。
- 第一阶段全通过后才进入 B。本轮完成固定上游 ParseJob 方法级核查，见
  [parsejob-method-audit.md](parsejob-method-audit.md)。没有重做 Sardine、取消/超时/清理修复。
- 实际发现并修复：上游 VodConfig 的非空解析列表会注入 type 4 Super 默认项，当前宿主
  以前却只选择首个配置解析器。新增 ParseCatalog 接入生产 Host，实现默认/命名/flag 子契约，
  首接口 500 时可使用后续有效 HTTP 解析器。空配置不激活自动解析，不自动执行 type 2/3 JAR。
  Super 名称对照了固定提交原版默认资源；没有改首页、TMDB UI 或授权边界。
- 回归贯穿实际配置解析、模型映射、ParseJob 和两个 MockWebServer，检查错误端点回退、
  请求头、命名/flag 筛选、空配置、未调用 JAR。红灯 3 项中 2 项失败；修复后定向通过。
- 本地完整 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过：
  **536 项 JVM，0 失败/错误/跳过**，lint 和 Debug 构建成功。静态检查及 diff 检查通过。
  日志：validation/2026-11-05-parse-config-{red,targeted,local-success}.log。
- 本断点随 Config 代码提交；最终 SHA 以 git rev-parse HEAD 为准。push 后的新 HEAD 会自动
  再跑同一串行 CI，不能把 f11be02 的成功继承到新提交。当前阶段 B 仅此子契约完成本地验证，
  整体 Config/Loader/PlayerManager 仍未完成，未进入 C/D。
- 下一条明确操作：查询新 HEAD 的 actions/runs，等待 Debug 成功后 Emulator 全量结果，
  失败优先读日志修复；全成功后下一小任务是 Loader 的当前授权 ClassLoader 与固定
  BaseLoader/JarLoader 生命周期契约核对及必要回归，不重复默认解析选择修复。
- 查询 push 运行应使用 github_fetch actions/runs?head_sha=<SHA>；专用
  fetch_commit_workflow_runs 过滤 pull_request，空返回不能证明 push 没启动。
- 额度仍无准确读取接口、无不足提示；本轮仅 CI 恢复 + 一个 Config 子契约，不扩大到大型移植。
  不虚构剩余百分比或后台自动唤醒。TCL T7L Ultra 未实测，P0 未完成。

下方是此前已验证的实现记录；当前操作以上方 CI 断点为准。

更新时间：2026-11-05 UTC。Codex Cloud，单代理串行；未使用 Superpowers。

## HEAD、分支与并发

- 分支：`codex/m471-tmdb-personal-to-tvbox-playback-20261102`；PR #80 保持 Draft，不合并。
- 本轮恢复起点：`b75e1f947dcfeb933ab332a19c73bdb883b757f4`。
- 最近已完成并推送的子任务：`8811c7575b37abadb19f39a3ed3c1ea0ee197dde`，Sardine 源码构建恢复。
- 记录时 HEAD：`8811c7575b37abadb19f39a3ed3c1ea0ee197dde`；下面第二项实质修复与本断点
  随下一提交保存。最终 HEAD 用 `git rev-parse HEAD` 和远端 ref 核对；APK 交付目录的
  manifest.json 记录其完整提交 SHA、源码树 SHA 和 APK SHA-256，不用旧提交冒充最终实现。
- 恢复前工作区干净、仅一个 worktree；每次提交前查询远端未发现外部更新，普通 push，
  未 force、未覆盖别人提交。没有改已验收首页、TMDB UI、NAS/云盘或第三方插件授权。

## 已完成：构建恢复（阶段 A 验证补齐）

JitPack 403 已确定为当前云端网络策略限制；只有官方状态/诊断读取工具，无网络配置写入口。
用户授权评估等效依赖后，改为在 app 中编译官方 Sardine v0.9 的全部 90 份原始 Java 源码，
固定提交 `d0af7ae8e7ee0654a763c4c6f638a5e98b1782e9`，保留 Apache-2.0、API、同版本
OkHttp/Simple XML 依赖和原 ProGuard 规则。APK 携带许可证；静态检查逐文件校验 Git blob。
没有删除 WebDAV 依赖功能，没有使用镜像、修改网络策略或绕过代理。

SDK 已恢复；环境原有 OpenJDK 21 缺 jlink，现已安装校验过的官方完整 Temurin JDK 17。
路径、工具版本、官方校验和与复现命令见 [cloud-build.md](cloud-build.md)。缓存可能随环境
重建消失，先检查实际文件。新源码依赖的真实 PROPFIND/XML、认证 Range/媒体字节和 403
回归均通过。既有 ParseJobDeadlineTest 的 finally 清理断言竞态已增加独立关闭事件等待；
保留超时、失败次数和关闭次数断言，没有删除测试或放宽生产超时。

8811c75 的本地完整测试/lint/Debug 构建通过，532 项测试，0 失败/错误/跳过。
失败与成功证据保存在 validation/，见 source-build-missing-jlink、parsejob-cleanup-race、
source-build-success 日志。不是只做文档记录；已提交源码构建配置及全部固定依赖源码。

## 已完成：ParseJob 宿主派发拒绝清理（阶段 B 的小任务）

复核固定 FongMi 上游 ParseJob.complete/deliverResult/stop：原版 App.post 被注入 Host.post
替代后，宿主可能拒绝派发，导致 deliverResult 的 finally 根本不执行。已有 done 标记还会
阻止后续截止时间回调，工作池和活动 WebView 因而残留。

- 新增真实 ParseJob 生命周期回归：先注册 WebView，随后拒绝最终结果；修复前稳定失败，
  错误为 parser worker must shut down immediately（红灯日志保留）。
- complete 捕获派发 RuntimeException/Error，立即 stop，保留原异常；不在错误线程派发 UI
  回调。回归同时要求两个工作池关闭、WebView 恰好关闭一次、重复 stop 保持幂等。
- 实际 Android 宿主通过 ParseUiDispatcher 检测 Handler.post 的 false 返回，转换为同一
  拒绝异常；回归使用这个实际 boolean 队列桥接，而不是仅让测试宿主抛异常。
- 保留 FongMi GPL-3.0 原作者与旧来源记录，增加宿主修改日期；43 份固定上游文件未改动。
  这是宿主适配修复，不宣称整套 Config/Loader/PlayerManager 已完整移植。

## 最终验证与产物

实际执行 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`，退出 0，
**BUILD SUCCESSFUL**。最终 JUnit XML：**533 项，0 失败、0 错误、0 跳过**；
lint 0 error、30 warning；Debug APK 已重新生成，内含 Sardine 许可证。
ParseJob 定向回归通过后才执行完整验证；没有运行未授权外部 Spider 插件。

- `validation/2026-11-05-parsejob-completion-red.log`：修复前确定性失败。
- `validation/2026-11-05-parsejob-dispatch-targeted.log`：最终宿主桥接定向测试。
- `validation/2026-11-05-parsejob-dispatch-success.log`：最终完整验证成功。
- 静态检查通过：43 份原版 FongMi、90 份原版 Sardine、旧代理生产边界。
- 产物保存在云端 `/workspace/artifacts/HERRIN-P0-<最终HEAD>.apk` 及 manifest.json；
  不提交 APK/SDK/密钥到 Git。当前代码出帧、声音、遥控器的 TCL 验收仍未发生。

## CI、剩余范围与下一条操作

GitHub Actions：8811c75 的 workflow runs 为空，PR mergeable=false；不能把本地成功当作
当前 HEAD 的远端 Debug/Emulator 成功。现有 push 分支筛选不含当前 P0 分支，这是可独立
继续修复的 CI 触发缺口，不能以合并 base/main 来解决，也不能删关键模拟器测试。

下一轮先读取远端 HEAD、此断点和 CI，检查并发。下一条明确操作：恢复当前 P0 分支的
串行 CI 入口，复用现有 Debug/TV Emulator 检查，要求 Debug 成功后才运行模拟器，并以
实际 SHA 记录 APK/测试结果。然后继续固定上游 ParseJob/Config/Loader 的接口差异闭合。
完整 PlayerManager/MediaItemFactory 仍依赖定制 Media3，见兼容矩阵，未进入 C/D 验收。

没有新的下载阻塞需要反复重试；不再要求用户先放行 jitpack.io。仍未取得最新提交的 TV
Emulator 或 TCL 真机真实 MP4/HLS/TS 出帧、声音和遥控器闭环证据，**P0 未完成**。

## 额度与续跑

本轮开始、首个子任务结束及收尾均检查官方可用工具：无准确额度/限额/恢复时间接口，
未出现额度不足提示，不虚构百分比。单轮完成两个串行且可测试的小任务后保存断点，
避免继续扩展大型移植。未创建定时自动化，不承诺后台自行恢复。
