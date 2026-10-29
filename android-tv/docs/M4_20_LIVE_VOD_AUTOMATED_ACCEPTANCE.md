# HERRIN M4.20 — 直播遥控器与 TVBox 节点播放验收

## 用户复现的五个高优先级问题

1. **直播全屏 D-pad 上下键**：上下键应直接跳转到上一／下一 *节目*，跳过同一节目的其他 URL；换台时无需弹出线路选择界面。
2. **默认直播线路**：选中 CCTV2 直接播放首个线路；如果曾经手动选择 CCTV2 的另一条线路，后来上下键返回 CCTV2 应优先使用它。
3. **TVBox 部分节点无集数**：CMS 列表页通常没有 `vod_play_url`；进入某片后必须请求 `ac=detail&ids=<原始 vod_id>`，随后显示所有可播放集数。
4. **播放失败**：必须区分 CMS 列表／详情、Spider 解析、解析页、媒体加载、解码与实际出帧；不能将“有播放 URL”当成“播放成功”。
5. **高清海报**：可选的合法元数据服务应严格匹配片名与年份，未授权令牌、匹配有歧义、网络超时、返回非 HTTPS 图片时必须降级到影视源提供的海报。

## 当前新增自动化测试

| 测试文件 | 自动化环境 | 证明什么 |
| --- | --- | --- |
| `M420LiveRemoteAcceptanceTest` | JUnit/JVM | 去重频道后，上下换台、首线路自动播放、手选线路记忆、首末循环、空列表安全 |
| `M420LiveDpadInstrumentedTest` | Android 模拟器 | 向全屏播放器真实注入遥控器上下键；节目名变化但不出现线路选择抽屉 |
| `M420TvBoxCmsDetailTest` | JUnit/JVM | 只有目录的 CMS 影片仍保留原始 ID；第二次详情取得全集；不同线路集数正确；URL 安全性 |
| `M420CmsDetailsIntegrationTest` | Android 模拟器 + 本地 MockWebServer | 确认 `ac=detail&ids=` 实际被请求，源 API 签名参数原样保留并拿到可播放集数 |

## 必须执行的 CI

```bash
cd android-tv
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

`connectedDebugAndroidTest` 必须由 GitHub Actions 上 Android TV 模拟器运行；在没有 Android SDK、模拟器或网络依赖的本地开发环境中不能自称通过。

## TCL T7L Ultra 实机验收（必须报告“未验证”或填写真实结果）

- 导入用户自己授权的直播列表，打开全屏 CCTV1；按 ↓ 是否立即播放 CCTV2；按 ↑ 是否立即返回 CCTV1；是否出现多余的选线路确认。
- 对一台有多条 URL 的节目，手动选择另一路，然后切走再切回；是否记忆线路；失效时是否安全尝试同一频道的候选线路。
- 导入用户配置的 TVBox 单仓；分别选取 CMS、Spider/JAR、特殊扩展等类型站点；记录每站点 `homeContent`、`categoryContent`、`detailContent`、`playerContent` 失败位置，脱敏后记录返回结构类型。
- 对确实具备授权可访问 URL 的影片，点击选集是否无需额外确认直接开始播放；开发者/诊断画面是否显示 Media3 `STATE_READY`、正数已输出视频帧、正常音轨；网络 `HTTP 4xx/5xx` 和解析页不可播放需要明确区分。
- 为任意两部已知中英别名的影片检查海报宽高、正确片名、年份和缓存回退；没有元数据 API 凭据时禁止声称“高清海报刮削已生效”。

## 权限、授权与验收红线

- 不内置任何未经授权的单仓、频道、在线播放 URL、网页解析器或服务令牌。
- TVBox 的网页解析标志 `parse=1` 不等于可直接交给 Media3 的流媒体 URL；没有合法直链时提示源侧还需解析，不能宣称解码成功。
- 主动运行用户指定的第三方 Spider JAR 需沿用项目现有的显式信任策略。
- CI 只能证明编译、模拟器虚拟机/原创建模媒体行为；**不能冒充 TCL 实机或用户当前单仓的真实在线播放成功**。
