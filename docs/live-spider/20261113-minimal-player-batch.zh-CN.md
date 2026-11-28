# 原版会话极简播放器开发批次

本轮起点77a30e46；最终验证代码83288e1a（文档提交不改变代码）。P0未完成。

## 底座比较与决定

| 标准 | A：保留HERRIN，完整原版库+adapter | B：完整原版app接入HERRIN |
| --- | --- | --- |
| 原版复用 | 已有whole-core源集直接复用原版状态机、解析、Spider、双后端；UI只调宿主端口 | 保留原版app可少一层library封装；仍需迁移HERRIN业务 |
| 编译 | 原版Android library存在本地AAR包装问题，已有immutable Maven staging处理；仍缺匹配媒体输入 | 同样缺定制Media3、MPV、FFmpeg、hook、tvbus合法匹配材料，原版app并不消除依赖阻塞 |
| 包名/Application | 已有KernelApplication继承原版，HERRIN壳同包适配类待切换时清理 | 仍须处理原版Application、HERRIN启动/CrashJournal与包名；不能假设改包即可运行 |
| Spider/网盘 | 保留VideoViewModel/VodPlaybackController和原版授权Activity；完整Android链接尚未验证 | 原版路径原生保留，但移植HERRIN路由和界面后同样要回归 |
| 升级 | 固定源码+窄宿主接口，可集中处理API变化 | app合并点增多，界面/业务迁移和后续上游升级冲突成本尚未实测 |

决定继续A的现有结构；没有证据证明B已真实可编译且更适合，因此不切换底座。
不能把本决定理解为A已编译成功或独立Kernel永不可变。本轮未再次下载/审计完整
305dff2；未重验已拒绝的两个候选。依赖锁artifacts仍为空，无新合法匹配二进制。

## 实际代码

- bd4f170b：AV1、TrueHD、DTS、DTS-HD平台查询；DV5/7/8分别按精确profile位
  查硬件声明。Profile7永不由此宣称完整双层。音频决策要求相同采样率/声道数，
  DIRECT_CANDIDATE不是实际透传证据，PCM候选也不是实际音响输出证明。
- 930dac15：whole-core端口显式选音轨、字幕及关闭字幕，调用同一PlayerManager.setTrack，
  不重建播放器。选择前校验当前group/index/type/support。停用宿主弹幕加载回调，
  不删除共享内部API。该adapter源尚受依赖阻塞，未宣称Android类型/链接通过。
- c3200fdb：KernelMinimalControls全屏透明黑控制层、白色控件、顶部返回/标题/设置，
  底部进度时间、左倍速、中间±10秒与暂停、右音轨/字幕。设置含线路/选集入口，
  轨道/倍速/技术信息独立面板。只在whole会话接线，不创建播放器。
- 4036c59c：原版Seek防溢出及片长边界，长按方向键时暂停自动隐藏；松开后约4秒。
  面板打开时暂停隐藏；方向焦点继续由Compose处理，中心按钮下键可达设置入口。
- eda76c11、d018cad5：后台查询当前选中音频精确格式，技术页区分轨道编码、平台候选、
  显示HDR声明与实际输出未知；空音轨安全处理。没有添加DV/HDR/Atmos播放徽标。
- 69561c06、83288e1a：统一宿主CI；旧失败断言只加固定事件序列诊断，条件未放宽。

这是可选whole会话的新增组件，稳定旧生产入口未切换；旧播放器删除0，无隐式fallback
接线。未改已验收首页/海报/导航/搜索范围及来源顺序。线路/集数内容继续使用既有
独立选择页面，本轮增加极简设置入口，未重新实现其网盘业务。

## 限制与待验

最终像素布局没有可用的SONY参考附件或截图实测，不能宣称视觉验收。文字/符号控件
仍需TCL真机确认字形和焦点反馈。新控制层尚未有TV仪器测试结果。视频比例继续FIT，
未强制刷新率/帧率或HDR色彩转换。没有自动按能力切换Media3/MPV、没有真机透传配置
证明、没有DV5/7/8合法样片播放实证。不能把能力查询当作这些功能全部完成。

本地缺SDK/javac/adb/emulator/KVM；宿主wrapper构建在下载Gradle时报沙箱SocketException。
原版wrapper未初始化（退出127），依赖预检INVALID，缺源码和四个AAR/定制Media3。
完整core/adapter没有编译。GitHub CLI API Forbidden，改用已连接GitHub读取CI与PR。
PR84实读open/draft/unmerged，head仍08f6640，位于旧global-search分支；未同步该分支。

遗留M476失败的静态根因：HerrinRemoteMediaProbe的正文读取异常由内层catch处理，
仅发MEDIA_BODY_IO_*与MEDIA_IO_*后返回NETWORK_ERROR；只有外层catch才发
MEDIA_FAILURE_+ioPhase。因此正文超时时不产生原断言要求的MEDIA_FAILURE_RESPONSE_BODY。
本轮保留旧路径不补丁的约束，没有修改该实现，也没有删除、跳过或放宽原断言。
最终CI原始JUnit确认delayHeaders=false，事件有MEDIA_RESPONSE_HEADERS_RECEIVED、
MEDIA_BODY_IO_TIMEOUT及MEDIA_IO_TIMEOUT，缺MEDIA_FAILURE_RESPONSE_BODY；恰为第33行失败。
根因已证实，不能认定flaky或算作已修复。

真机TV/生命周期/字幕音轨/HDR/音频回归、ABBA及奶酪/玩偶均未执行。
没有新合法源端依据及新完整内核设备，不重复已知403/空正文。无首帧/60秒/Seek/重播/
切集/返回详情通过，无QR或AUTH成功证明，没有验收APK。

## 最终统一结果

run43378025637 / job131729029449 / 83288e1a40eb3fd931a8e37b794bb037a3041ef7：
contract、宿主Kotlin/Java编译、Lint和Debug assemble通过。
原始JUnit汇总727 tests / 1 failure / 0 errors / 0 skipped；新增媒体能力2/2、Seek1/1通过。
唯一失败仍是上述M476。完整工作流FAILURE，失败后的adapter源码审查步骤skipped，
不算通过。工件14016120847已下载逐XML核查，ZIP SHA256
`d4180ada39b663acc663483e817f192a9f4ee3c4bff4d83c2c6b620dd8199333`。

https://github.com/shawnhhh/aura-tv/actions/runs/43378025637

原版core/adapter：BLOCKED，不能以宿主成功替代。TV与真实节点、杜比专项均未执行。
准确续跑命令见P0-CONTINUE.md最新首段。
