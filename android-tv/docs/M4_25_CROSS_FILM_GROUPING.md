# HERRIN M4.25 — 同一影视目录中跨条目多季整合

版本：`0.4.25-alpha`，`versionCode=32`。只修改 Android TV 端；保留 Draft，不合并主线。

## 本阶段实际支持范围

- **只在用户当前选中的一个 CMS 或 Spider 影视点已经载入的列表内**，把同一剧集不同 Film 条目合成一张作品卡。没有跨影视点扫描、跨站点缓存或后台联网搜索其他来源，不能称为全网去重。
- 统一海报墙、推荐、搜索、分类和收藏入口按作品去重。搜索时先匹配所有原始条目，再展示一张作品卡，因此搜索「第二季」仍可找到该作品。展示标题采用可信基础剧名，季名称不作为作品标题。
- 原始 Film ID、原始集数、原始线路 ID/URL、原始影视点上下文始终保留。详情季标签与 16:9 单集卡从可确认的真实选集生成；点击集数直接调用原来的 CMS/Spider/Media3 播放流程，备选线路可单独点选。
- 原始条目列表通常没有选集。进入合并详情后仅向**本次作品最多 8 个成员**按需请求现有 CMS/Spider `details` 接口，每个成员最长 3.2 秒，离开详情即取消。不会为了合并整个首页批量读取全部视频解析地址。
- 如果条目无可确认的季集结构，详情保留「查看原始条目」入口，切换回该 Film 原有选集/线路 UI，不为其猜测季、集或播放器 URL。
- 收藏与最近播放均保留原 Film+episode+line 的旧本地存储键；组内任一成员已收藏即展示收藏状态，取消时移除所有已收藏成员 ID；历史回到具体来源与线路。
- 先用可信 TMDB TV ID（同目录、TV 分类）合并；缺少该 ID 时，只有严格相同的基础片名、明确季数证据、合理年份/类别兼容才合并。不会依靠模糊文本相似度把跨站点/翻拍/OVA/剧场版/Part 混成一部。
- 沿用用户自行配置的 TMDB Read Token 获取官方海报与单集信息；不开启额外元数据授权、外部刮削站、未经授权的豆瓣抓取。

## 明确不在本阶段范围

1. **跨多个影视点一次性整合**：当前内存只保留一个已选影视点的 `providerFilms`；涉及多站增量索引、独立错误隔离和持久身份映射，需要另立阶段。
2. **手工合并/拆分的永久纠错界面**：需要迁移、冲突解决和可靠存储方案；此阶段不展示无实际动作的假按钮。
3. **无法映射季集的来源自动生成虚构季和全集**：仅展示其原始条目入口；同名重拍、Part、OVA 会保守不合并。
4. **保证所有 TVBox 仓库节点均可解码和播放**：影视源的 Spider/JAR、受保护网页、鉴权和播放格式仍受现有适配器/网络/硬件支持约束；本版本没有声称修复所有节点。
5. **Netflix/网易爆米花私有代码或版权素材**：没有复制。视觉与元数据只借鉴产品结构，图片由已有合法 TMDB 配置提供。

## 回归重点与测试

`M425ShowGroupsTest`（JVM）覆盖：中文/英文明确季标、严格分组和负例、可信 TMDB ID 别名、同一季不同来源、同一 Film 多线路季加独立第 3 季、重排与元数据更新下稳定 groupKey、尚未加载详情到获取第 2 季第 3 集的**原始 Film B**路由、搜索与收藏去重、历史保留。

`M425GroupDpadInstrumentedTest`（Android TV Compose）：遥控器从第 2 季 Tab 按下进入 16:9 选集并按确认触发原始 Film+line；无法映射季的条目点击「查看原始条目」切换到旧选集并完成原始路由。

执行建议（需有 Android SDK 与已缓存 Gradle）：

```bash
cd android-tv
python3 scripts/check_static.py
./gradlew --offline :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=tv.herrin.app.M425GroupDpadInstrumentedTest
```

当前云端工作区若缺 Android SDK，无法将静态检查误称为真实安装/遥控器测试通过。上线前须以 GitHub CI 与 TCL 实机重复验证。
