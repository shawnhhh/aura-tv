# M4.61 FongMi 配套运行时签名测试

HERRIN 原有 Android Debug/Emulator CI 验证播放协议、受控出帧和 UI 回归；它们**不代表**同 FongMi 播放器完全兼容。

## 配套运行时 APK 的独立 CI

新增 `.github/workflows/herrin-fongmi-runtime-pairing.yml`：
- 使用相同的一次 GitHub Actions Debug 签名生成 HERRIN 宿主 APK；
- 从 FongMi/Release 官方 `module` tag 获取 arm64-v8a QuickJS 1.0.8、Python 1.1.1、Node 1.0.5 和 MPV 1.1.0；
- 使用固定的每个官方 APK 的 SHA-256 防篡改，并逐一核对 `assets/runtime.json` 的 packageName/module/compatibilityId；
- 用本次宿主 Debug 签名重新签名运行时 APK，对比宿主及模块的 Android 证书 SHA-256；
- 仅在全部校验通过后，上传带说明及 SHA-256 校验文件的配套包；
- 不修改 Herrin 的 UI/TMDB/影片资料，也不关闭运行时包签名验证。

**重要：单独下载原版 Debug APK，再单独下载运行时模块不保证签名一致。必须使用同一配套 CI 产出的 Herrin 宿主与模块。** 配套构建仍不能证明各种插件真的执行成功；后续必须做实际运行时加载、脚本播放、字幕、网盘认证、视频出帧以及 MPV 选核验证。TCL T7L Ultra 真机也必须单独测试。此工作流只制作 arm64 配对模块，armeabi-v7a 的适配仍需补充。

FongMi/TV 及 CatVod GPL-3.0 源码与归属请参照上游：https://github.com/FongMi/TV 以及 https://github.com/FongMi/Release/releases/tag/module 。重新签名的 APK 仅用于兼容性测试，不是 FongMi 官方签名发行物。
