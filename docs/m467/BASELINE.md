# M4.67 基线和隔离记录

- Herrin remote: https://github.com/shawnhhh/aura-tv
- 原始工作树 `/workspace/aura-tv`: `work`，HEAD `7c768bb8beb8c6507700f39e89f2776be4c4509f`，开始时 clean；保持原位。
- 先执行 fetch。原 origin fetch refspec 仅 master，所以再显式 fetch 用户指定两条 fix 分支。
- PR #75: Draft/open，head `fix/herrin-m466-tv-dpad-detail-cast-tmdb-20261101`，真实 SHA `cd6deb3d116969f36e451be6ba6dd8649dc4b23f`。
- PR #74: Draft/open，head `fix/herrin-m465-fongmi-proxy-compat-long-stream-20261101`，真实 SHA `5875064cb399433a81fb81e0ec2d61df1f288a10`。
- #75 依赖 #74。`git log #75..#74` 仅出现 `5875064` 的静态代理测试修正；`git diff 5875064 cd6deb3 -- android-tv/app/src/androidTest/java/tv/herrin/app/SpiderLocalProxyPlaybackTest.kt` 为空，说明修正已独立带入 #75。不合并 main、不改变 #74/#75。
- 上游官方 `FongMi/TV` `fongmi`: `5e191aaced194e5cac5668cc91908ecb65336222`（固定，不追踪漂移）。
- 公共约定：PlaybackMedia/PlaybackResult、router 和 Spider remote 保持调用兼容；新增可选参数/重载。代理/worker 归 Agent1，解析器归 Agent2，引擎归 Agent3，恢复和必要播放连接归 Agent4。

| 工作任务 | Worktree | 分支 |
|---|---|---|
| 代理/Spider | `/workspace/herrin-m467-spider` | `codex/m467-spider-proxy` |
| ParseJob | `/workspace/herrin-m467-parsejob` | `codex/m467-parsejob` |
| 播放引擎 | `/workspace/herrin-m467-engine` | `codex/m467-player-engine` |
| 错误恢复 | `/workspace/herrin-m467-recovery` | `codex/m467-playback-recovery` |
| 总集成 | `/workspace/herrin-m467-integration` | `integration/herrin-m467-fongmi-full-playback` |

所有 worktree 从上述 #75 SHA 独立创建。子 Agent 不修改总集成树。主 Agent 审查各自提交后顺序 cherry-pick，必要冲突只在集成树解决。

原创 DEX fixture 的 Java source、compile-only ABI stub、构建脚本都在 `android-tv/test-fixtures/m467` 和 `android-tv/scripts/build_m467_spider_fixture.py`；只有 fixture 类进入 DEX，stub 不进入，媒体来自原有原创 synthetic.mp4。JAR 仅在 androidTest APK 中。测试通过生产完整 MD5 校验的缓存路径加载，不放松生产 HTTPS/鉴权策略。
