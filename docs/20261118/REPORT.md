# 2026-11-18 P0 CI 13项失败专项修复

修复前 HEAD：`0a0623ed424d187fad78b8127b5f51d28f909700`。目标分支：`codex/herrin-p0-live-playback-20261110`。工作区初始干净，无未推送修复。

原始证据：[CI 44084378548](https://github.com/shawnhhh/aura-tv/actions/runs/44084378548)、job `134042903581` 完整日志、工件 `14330173944` 中完整 JUnit。大工件14331061455的下载URL返回403，改用同次运行的较小工件读取相同JUnit，没有以推测替代异常。原始13项失败堆栈保留在 [JSON](ci-44084378548-failures.json)。

9项生命周期失败都发生在 `ActivityScenario.close → moveToState → waitForActivityToBecomeAnyOf`，最后状态RESUMED。结合测试共同修改action以及AndroidX core 1.6.1 `ActivityScenario.java:475/553`：启动Intent与Activity Intent不匹配时回调被忽略。未修改MainActivity生命周期、启动、返回或Debug诊断访问限制。新测试Rule先关闭旧Scenario，再以正确诊断Intent创建并持有新Scenario，统一隐藏IME/清除编辑焦点并close；移除手工finish补丁。设备复验前不将此判为验收通过。

| 测试类/方法 | 原始失败 | 根因及修复 | 修复后设备验证 |
| --- | --- | --- | --- |
| `EmptySpiderHomeSelectionTest#advancedPreviewRetainsEmptyInitializedSource` | java.lang.AssertionError: Activity never becomes requested state "[DESTROYED]" (last lifecycle transition = "RESUMED") | 已启动Activity的intent.action被修改，再recreate；Scenario按旧启动Intent匹配，销毁回调被忽略。由MainActivityScenarioRule.openDiagnostics重新启动独立Scenario，保留原断言。 | 待设备定向复验 |
| `HerrinUiSmokeTest#diagnosticsNeverTreatRefreshHintAsMeasuredFps` | java.lang.AssertionError: Activity never becomes requested state "[DESTROYED]" (last lifecycle transition = "RESUMED") | 已启动Activity的intent.action被修改，再recreate；Scenario按旧启动Intent匹配，销毁回调被忽略。由MainActivityScenarioRule.openDiagnostics重新启动独立Scenario，保留原断言。 | 待设备定向复验 |
| `HerrinUiSmokeTest#settingsExposeNativeSourcePickerWithoutReplacingExistingControls` | java.lang.AssertionError: Activity never becomes requested state "[DESTROYED]" (last lifecycle transition = "RESUMED") | 已启动Activity的intent.action被修改，再recreate；Scenario按旧启动Intent匹配，销毁回调被忽略。由MainActivityScenarioRule.openDiagnostics重新启动独立Scenario，保留原断言。 | 待设备定向复验 |
| `HerrinUiSmokeTest#liveGuideIsOptionalAndConfiguredViaRemoteSettings` | java.lang.AssertionError: Activity never becomes requested state "[DESTROYED]" (last lifecycle transition = "RESUMED") | 已启动Activity的intent.action被修改，再recreate；Scenario按旧启动Intent匹配，销毁回调被忽略。由MainActivityScenarioRule.openDiagnostics重新启动独立Scenario，保留原断言。 | 待设备定向复验 |
| `HerrinUiSmokeTest#settingsPersistReducedMotion` | java.lang.AssertionError: Activity never becomes requested state "[DESTROYED]" (last lifecycle transition = "RESUMED") | 已启动Activity的intent.action被修改，再recreate；Scenario按旧启动Intent匹配，销毁回调被忽略。由MainActivityScenarioRule.openDiagnostics重新启动独立Scenario，保留原断言。 | 待设备定向复验 |
| `HerrinUiSmokeTest#mediaSettingsRemoteSaveValidationSyncAndPersistence` | java.lang.AssertionError: Activity never becomes requested state "[DESTROYED]" (last lifecycle transition = "RESUMED") | 已启动Activity的intent.action被修改，再recreate；Scenario按旧启动Intent匹配，销毁回调被忽略。由MainActivityScenarioRule.openDiagnostics重新启动独立Scenario，保留原断言。 | 待设备定向复验 |
| `HerrinUiSmokeTest#settingsOfferExplicitSourceBackupImportAndSafeDeleteFlow` | java.lang.AssertionError: Activity never becomes requested state "[DESTROYED]" (last lifecycle transition = "RESUMED") | 已启动Activity的intent.action被修改，再recreate；Scenario按旧启动Intent匹配，销毁回调被忽略。由MainActivityScenarioRule.openDiagnostics重新启动独立Scenario，保留原断言。 | 待设备定向复验 |
| `HerrinUiSmokeTest#tvBoxMultiwarehouseAcceptsHttpByDefaultAndNavigatesWithRemote` | java.lang.AssertionError: Activity never becomes requested state "[DESTROYED]" (last lifecycle transition = "RESUMED") | 已启动Activity的intent.action被修改，再recreate；Scenario按旧启动Intent匹配，销毁回调被忽略。由MainActivityScenarioRule.openDiagnostics重新启动独立Scenario，保留原断言。 | 待设备定向复验 |
| `HerrinUiSmokeTest#liveTabExplainsAuthorizedPlaylistAndSettingsProvideItsUrl` | java.lang.AssertionError: Activity never becomes requested state "[DESTROYED]" (last lifecycle transition = "RESUMED") | 已启动Activity的intent.action被修改，再recreate；Scenario按旧启动Intent匹配，销毁回调被忽略。由MainActivityScenarioRule.openDiagnostics重新启动独立Scenario，保留原断言。 | 待设备定向复验 |
| `P0MetadataPublicationPressureTest#validProbePublishesHundredsThenTop250WhileCategoriesAndRemoteRemainUsable` | java.lang.AssertionError: Requests must stay bounded, including cancelled/restarted pages | 分类进入/取消重启的LaunchedEffect无条件force=true，绕过新鲜页及短请求缓存。改为常规缓存刷新；显式重试仍强制刷新，预算保持<70。 | 待设备定向复验 |
| `TmdbBrowseLoadingNavigationTest#nextPageReturnAndReplacementKeepNavigationOnCurrentPosters` | androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 5000 ms | Discover 夹具遗漏 variant；强制刷新仍返回电影11，测试等待电影12必然超时。修正原始夹具，使真实列表替换发生。 | 待设备定向复验 |
| `TmdbBrowseLoadingNavigationTest#coldFirstPagePreviewHasStableUniqueKeysWhileProofAndDpadOverlap` | java.lang.AssertionError: Failed to assert the following: (Focused = 'true') | 原序列先Down跨到国内栏，再Right，却断言热门栏电影72。先Right断言热门第二张，再Down验证国内同列101。 | 待设备定向复验 |
| `TmdbBrowsePageTest#nativeDpadTraversesIndependentRowsAndReturnsToTheSameColumn` | java.lang.AssertionError: Failed to assert the following: (Focused = 'true') | HOT未知发行证据时末尾有重试按钮，原断言把Right一步当作加载更多。补充真实重试焦点断言，再Right验证加载更多。 | 待设备定向复验 |

## 请求预算证据与边界

旧JUnit只保留预算断言失败，没有输出准确计数或请求路径，所以旧运行只能确定总请求数≥70，不能编造分类计数。修复后压力用例增加真实fetcher请求总数、path分组及相同URL重复次数日志，并把这些数据加入原<70断言信息；没有改变阈值。新增JVM回归覆盖缓存恢复、重复分类进入、重建状态零新增请求，以及显式重试真正刷新内容。现有并发上限3、发行证明上限2与影片筛选规则保持不变。

## 验证

主代码Kotlin/Java编译、AndroidTest Kotlin编译通过。统一Gradle命令只运行 `TmdbBrowseRepositoryTest`：21/21通过，0失败/错误/跳过；包含新鲜缓存恢复/重复分类进入零网络请求与显式重试仍更新Discover内容。静态结构检查及4项播放边界检查通过，`git diff --check`、13方法过滤器和shell语法检查通过。完整结果见 [verification.json](verification.json)。无模拟器/KVM，不安装模拟器；`android-tv/scripts/recheck_ci_44084378548.sh`严格从登记表生成13个方法的Instrumentation过滤器，检测不到设备时明确退出。

完整回归未重新验证。未手动运行270项测试或TMDB ABBA；未生成APK。集中推送可能由仓库原有门禁自动启动完整CI，未修改或禁用这些门禁。最终远端状态以交付时查询及对应提交为准。

## 修改范围

仅MainActivity分类刷新调用属于生产代码修改。其他改动为诊断Scenario管理、上述3项焦点夹具/时序、预算证据、JVM回归与13项专用复验脚本。未改首页/设置UI、启动动画、资源搜索架构、TMDB业务筛选或FongMi核心。

Draft PR #85保持Draft，不合并main/master，不force push。

## 实际修改文件

- `android-tv/app/src/androidTest/java/tv/herrin/app/EmptySpiderHomeSelectionTest.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/HerrinUiSmokeTest.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/MainActivityScenarioRule.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/P0MetadataPublicationPressureTest.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/TmdbBrowseFixture.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/TmdbBrowseLoadingNavigationTest.kt`
- `android-tv/app/src/androidTest/java/tv/herrin/app/TmdbBrowsePageTest.kt`
- `android-tv/app/src/main/java/tv/herrin/app/MainActivity.kt`
- `android-tv/app/src/test/java/tv/herrin/app/TmdbBrowseFixture.kt`
- `android-tv/app/src/test/java/tv/herrin/app/TmdbBrowseRepositoryTest.kt`
- `android-tv/scripts/recheck_ci_44084378548.sh`
- `docs/20261118/REPORT.md`
- `docs/20261118/ci-44084378548-failures.json`
- `docs/20261118/verification.json`
