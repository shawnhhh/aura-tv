# 整体内核连续开发检查点

策略：先连续开发，后统一验证。起点 b37a4b9a；上游305dff2复用，不重下载/重审计。
禁止Superpowers、节点重测、TV/ABBA循环、主分支合并和未出帧的P0成功声明。

## 模块A：独立构建骨架

新增 android-tv/fongmi-kernel 独立 Gradle 构建，直接引用完整原版 CatVod 模块及
app/src/main、leanback Java/resources/assets/aidl/jniLibs。原版源码不拆解、不重写，
SiteApi/VodConfig/Spider Loader/ParseJob/Proxy/PlayerManager/Exo/MPV均在源集内。
保留完整上游版本表、Java21/AGP9.4/SDK37，Room/EventBus/Glide处理器与原依赖。

电视flavor类是共享代码的编译依赖，保留源码而不注册其首页/设置/直播入口。
库清单只声明必需runtime可见性、内部WebActivity及NodeService；宿主品牌与界面不变。
应用ID构建常量为tv.herrin.app，不伪装原包名/签名。

状态：模块构建配置已写，尚未完整编译。未与旧app混编，未切换执行路径。
下一模块：B依赖来源/输入锁，随后C原版委托适配接口、D单所有者切换控制。
未解决：定制Media3及MPV/FFmpeg AAR来源、版本与许可；旧app同包类冲突需迁移后才能接入。

## 模块B：依赖边界和确定性输入

已查找到实际公开来源 FongMi/media（release默认分支不是定制版本）、其
release-1.11.0-fongmi@3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d，及
FongMi/mpv-android、mpv、FFmpeg。该Media树虽然有mpvplayer/decoder_ffmpeg模块，
但缺少本TV提交需要的四组关键定制API，不能当作已匹配的依赖。官方5.8.7
immutable release仅列四个APK，没有AAR或源码构建对应清单；未下载/测试原版APK。

dependencies.json记录这些确定事实与未解决版本；verify_dependencies.py检查显式输入
校验和、对应源码commit、许可材料、重复类、非空实现、双ARM JNI与原版Media3导入。
只约束完整内核preBuild，不拦截独立contract开发。没有替代AAR/桩类或删除原功能。
下一模块：C，原版SiteApi/PlayerManager直接委托与不携带媒体URL的UI接口。

## 模块C：原版委托与宿主边界

新增纯Java17 Kernel接口：仓库/明确站点执行授权、搜索、详情、原版选集引用、播放、
暂停/恢复/Seek/停止和状态观察。界面仅得到不透明影片/选集handle，得不到playerContent、
解析JSON、Proxy或媒体URL；适配层没有解析器、HLS处理或媒体探测。

FongMiKernel直接调用Config.find→VodConfig.load、SiteApi.searchContent/detailContent/
playerContent→原PlayerManager.parse，选集取自原Vod/Flag/Episode。独立IO串行执行、
主线程管理播放器，session epoch取消旧结果；原版仍负责超时/引擎/解码/释放。
原生PlayerView由原manager.bindPlayerView绑定。真实first-frame事件与READY分离。
授权界面继续由原App/CatVod/SpiderDialog负责，不合成QR或授权成功状态。

KernelApplication继承原App初始化Runtime/Startup，拒绝包名/签名Hook替换。
唯一源码宿主补丁是构建时将原Nano监听super(port)改为super("127.0.0.1", port)，
保留所有原process/Proxy响应/HLS字节语义；不暴露原版未认证的LAN管理面。
修正完整资源导入为原main/leanback层级覆盖，保留原flavor生成Binding与R。

状态：委托代码已写，Android编译尚未验证；当前app未接入，绝不说已替换。
下一模块D/E：宿主统一网关、单所有者无回退切换边界、保持稳定app；之后统一检查。

## 模块D/E：切换协议与稳定宿主

KernelCutover只有单向状态机：NOT_INSTALLED→RETIRING_LEGACY→INSTALLED，失败为
FAILED，关闭为CLOSED。旧会话退休future完成后才允许分配新kernel；没有旧工厂或
隐式恢复入口。宿主新增HerrinKernelGateway和纯contract依赖，可供现有页面调用，
没有修改UI/TMDB/源优先顺序/直播/遥控。snapshot直接读原manager位置与时长。

**尚未实际切换或删除旧代码**：外部Media3定制API不匹配，完整kernel不能编译；
按用户D“完整可编译路径前不要破坏当前稳定分支”，当前Application和页面继续原入口。
最终接线仍需宿主工具链/同包类/stock Media3迁移，改继承KernelApplication、保留
CrashJournal、绑定原PlayerView，将页面改接gateway后再删除旧实现。新增类不是已经接管。

本批构建边界、API委托与宿主接口已连续提交；进入统一静态/最小编译检查。
依赖输入问题只影响完整Android构建，不重跑环境审计、节点/模拟器/TV/ABBA。

## 授权宿主、原版VOD状态机与尚未接线的部分

进一步发现原版SpiderDialog要求FragmentActivity，而当前MainActivity为ComponentActivity。
新增不导出的KernelAuthorizationActivity，用原SpiderDialog显示真实网盘对话框，原回调
原样传递，Intent仅有随机ticket；options/回调只在内存中，取消/销毁均释放。
没有QR夹具、伪造授权或重签包。原WebActivity配置原Theme.App，避免继承宿主Compose主题。

新增OriginalVodSession，实际绑定原VideoViewModel→VodPlaybackController→
VodPlaybackState及原LiveData详情/播放/预载/搜索回调，委托选线/选集/重播/切集/
Seek通知/进度/历史/生命周期，不自行实现网盘展开选集算法。

**必须注意：这一绑定尚未与Kernel句柄接口和实际UI统一。** FongMiKernel现有直接
SiteApi→PlayerManager委托只是初步适配，不含完整VodPlaybackController的网盘动态
选集、历史和预载调度。不能以类存在或这个初步play方法声称完整原版链路已可用。
最终应由OriginalVodSession成为唯一VOD调度路径，补齐UI渲染Host，将playerContent
与原版状态机统一，移除初步直接调度；不可同时调度同一播放请求。

## 本批统一验证结果

- 本地无javac，使用Maven Central原版ECJ3.42.0（下载到/tmp，不入仓）最小编译
  Java17 Kernel/KernelCutover和边界检查；通过。
- 单次边界检查通过：旧会话退休前0个新分配，退休后唯一分配，close幂等，
  退休失败不创建新引擎，不允许隐式重试，关闭未完成切换可终止等待，
  缺类LinkageError为终态失败，HTTP及Spider执行必须显式授权。无任何媒体播放模拟。
- ECJ解析4个Android适配Java源通过；**仅语法，未验证Android类型/链接**。
- Python依赖验证器语法、两份AndroidManifest XML及git diff --check通过。
- 依赖输入统一检查明确返回
  `DEPENDENCY_BLOCKED: no source/version/license-locked external artifacts`，退出2。
  不生成假AAR或绕过门槛。完整Android编译、JVM应用回归、TV、ABBA及真实节点均未运行。
  当前不以模拟器或403作为开发阻塞；外部依赖映射和实际UI/controller整合仍未完成。
- 原UI/TMDB/NAS/WebDAV/SMB/直播/遥控源码未修改；没有执行回归，不能保证不存在构建风险。
- 旧实现已替换/删除清单：0。未提供验收APK、未合并主分支、未声明P0完成。

## 准确续跑

读取最新HEAD与此记录，不重新拉取TV源码、不跑1360文件审计、不测原版APK/旧403。
复用android-tv/fongmi-kernel的4模块和已有源码，优先完成OriginalVodSession与句柄API/
渲染Host的单路径整合，避免保留两套调度入口。取得TV305dff2所需定制Media3及MPV/
FFmpeg真实源码/版本/构建对应关系后，填dependencies.json真实artifacts并完整编译；
修复类型/链接问题，再接入当前Application/页面/PlayerView，移除旧同包类和stock Media3。
完整可编译前不启用新入口；之后旧会话完全退休→新kernel唯一分配→删除旧链路。

构建命令见android-tv/fongmi-kernel/README.md。统一编译完成再做应用JVM、TV和双节点
真实首帧/60秒/Seek/重播/切集；真实QR仅表示授权流程，未出帧不得通过。
Codex额度无可查询接口，余额未知；没有自动续跑机制，没有声称可自行唤醒。
