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
