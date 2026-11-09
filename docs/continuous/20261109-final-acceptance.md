# Herrin 六阶段统一验收说明与真机清单

本次第三、四、五阶段已分别实现并提交；阶段一、二的已验收代码不重新开发。最终事实回执由同一提交的GitHub Actions生成，不凭本文推断通过。Debug的HERRIN-test-reports与TV的HERRIN-emulator-evidence均包含中文验收报告、准确源码SHA、实际JUnit计数、APK校验和。必须联合确认JVM/Lint/Debug、Android TV、Android12启动及同机ABBA，不能把某一作业的0测试当作通过。

## 分支与交付对应

仅在codex/herrin-global-search-20261107与Draft PR #84追加普通提交，不合并主分支、不强推。最终SHA取产生通过报告/Debug APK的实际Git HEAD。最新APK下载HERRIN-debug-apk，校验和见同作业中文报告；它不应与不同提交的APK混用。全部CI链接保存在本目录20261109-stage2-ci.json、20261109-stage3-6-ci.json及PR，失败与重试记录保留。

## 完成实现

1. 播放P0：保留6e53648已验收的唯一FongMi PlayerManager/ParseJob/Proxy/Spider链，真实MP4/MKV/16:9/21:9/4:3边框、超时/取消、返回与授权策略。43个上游文件固定一致检查继续强制。与上游仍有私有Media3 fork、MPV/libass、光盘/专有AAR等差异，不伪装具备缺失能力。细节见stage1-audit。
2. TMDB：保留022dc323已验收六页22独立栏目，/trending/all/week与/trending/{movie,tv}/week，独立/discover、生产地区8/2/2去重、/movie/{id}?append_to_response=release_dates数字/电视证据、TV实际播出、300/100票门槛。页6h/证据24h缓存、64页/512证据上限、栏目并发3、Hero original/海报w500、焦点刷新延后、动漫可靠ID/系列缓存保留movie/tv身份。Top250独立，直播独立。细节见stage2-tmdb。
3. HTTPS：删除HTTP配置入口。用户仍可从HTTPS保存HTTP/HTTPS中文影视仓。设备生成私钥，以不可导出AndroidKeyStore AES-GCM包装后保存在noBackup目录；同IP稳定，SAN/到期/损坏换新。TLS验证不关闭，固定自签名不等于Safari信任。电视一次性确认、配对3分钟、服务最长30分钟/退出后台关闭、原信息与独立凭据保留。云盘WebDAV及SMB共用NAS页，均不自动批准Spider。
4. 启动：系统黑底、Android12透明原生icon，1800ms中心羽化显现→完整白字→至少2400ms全字柔光呼吸→本地缓存/首页已准备后340ms交叠淡出。VSync、预分配矢量画笔，零每帧bitmap分配，不缩放/平移；减少动态/明确跳过例外。7帧像素验证、六字同步/笔画外光、迟到首页/过渡非黑、真实FrameMetrics与API31冷启动独立记录。
5. SMB：已有SMBJ0.15.0，SMB2/3、签名、可选SMB3加密，禁SMB1/DFS转发；严格共享根路径、加密单记录与失败关闭修订，换地址不沿用凭据。最多32目录/深度3/总2000项/80匹配，常见容器、片名/年份/SxxExx。随机127.0.0.1能力URL，64位Range、1MiB分段、有限线程/队列/LRU、一次断线恢复/文件版本检查/释放。复用原个人选集与FongMi核，NAS(SMB/WebDAV)→云盘→首选影视点→其他影视点。
6. 统一回归：原有所有用例保留，新增协议/密钥/逐帧/真实SMB解码与5GiB偏移、安全配置负例。修复集中提交，完整通过须以同提交CI为准。不是删除测试、放宽断言或用元数据冒充播放成功。

## 自动化证据范围

- 受控真实Samba：1445仅SMB3且强制加密，1446仅SMB2，认证/签名/只读/回环；JVM真实字节、错误认证、取消、断线后偏移恢复、NAS优先级及>5GiB Range。稀疏标记文件不当作实际MKV电影。
- TV实际SMB3：原始MP4搜索/字节验证/FongMi出帧/Seek，MKV、TS及由原创TS包构建的M2TS真实出帧，Android侧>5GiB偏移。未接入用户私有NAS。
- 手机Chromium 393x852：真实配置assets与受控HTTPS API路由；一次配对、HTTP中文影视仓、混合批量、WebDAV/云盘/SMB域与加密选项、密码清空、结束会话。它不是Safari，也不替代Android服务真实TLS测试。
- Android12独立冷启动：同提交APK，系统原生交接/时序/录屏与遥控焦点。录屏仅作视觉证据；性能取未录屏时序。Compose驱动ABBA用于缓存页面/按键比较，不能解释为正常启动动画真实时长。
- 启动增加一次完整呼吸使设计最低动画约4540ms，不能宣称比原2秒更短；首CPU绘制/焦点指标及全部样本公开。软件渲染AVD不代表TCL显示扫描/4K/HDR吞吐。

## TCL / iPhone / 家庭NAS 待真机复验清单

- TCL T7L Ultra：冷启动无预绘浅字/第二字标；中心渐显、完整呼吸、迟到首页继续呼吸与平滑过渡；减少动态设置；切换六页与跨四栏、分页末端、返回详情/播放器焦点。
- 正常16:9、21:9、4:3完整等比，无裁切/拉伸，返回原详情。真实第三方各节点日志需区分空地址、鉴权、解析、代理/头/Cookie、网络/超时、媒体访问与解码；未提供真实节点日志不能称全部修好。
- iPhone Safari：相同IP多次开启指纹稳定，IP变化显示新指纹，系统证书告警与用户明确验证/信任；无法验证时用遥控器。需要无警告必须有有效系统信任链与匹配地址部署，不关闭安全校验。
- 实际NAS：SMB2/3、域/账号错误与正确、加密选项、文件夹/剧集、MP4/MKV/M2TS/TS、长文件Seek、网络中断/恢复与退出释放；WebDAV/SMB优先级及凭据隔离。
- 高码率4K、HDR/Dolby/音响输出受NAS实际带宽、TCL解码与系统能力约束，需原始文件/真实设备；CI短片与稀疏文件不证明这些性能。

没有实际自主定时开发机制；不会把GitHub正在运行的CI说成模型已安排自动续跑。需要资源检查点时保留精确HEAD、未通过测试和下一条命令。

### 集中回归：M2TS 实际失败与修正

5a3a875 的TV执行257项，仅M2TS失败（ERROR_CODE_PARSING_MANIFEST_MALFORMED），MP4/Seek、MKV、TS及其余256项通过。标准Media3 TS提取器不接受BDAV每包4字节时间戳；SMB数据访问层现在将192字节BDAV暴露为等价188字节TS视图，原有效载荷/PTS不变，随机偏移映射为物理SMB读取，无全量下载或第二播放内核。每次最多512包/96KiB、探测不超过960字节，截断/同步错误关闭；普通188字节TS不转换。新增任意偏移、超过5GiB逻辑偏移、短传输读取及错误布局用例；原真实SMB M2TS解码断言保留，需最终TV实际通过。
