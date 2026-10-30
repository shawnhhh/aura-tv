# HERRIN M4.51 — 区分遥控器浏览与文本编辑，避免输入框抢焦点

## 从电视源输入交互检查中发现的问题

`TvSourceInput` 通过一个**可聚焦的导航按钮**与一个**按 OK 才启动的真实文本编辑器**，专门解决 Android TV 的 IME 会吞掉下一次遥控器方向键的问题。

但紧凑设置父 `LazyColumn` 的 `onPreviewKeyEvent` 之前只判断“当前是设置页 / 是 KeyDown / 上下方向键”，不判断编辑器是否已经获得焦点。Compose 的父级预览事件先于嵌套 TextField 分发，因此用户刚按 OK 进入编辑时，上/下键仍可能被外层列表抢走、转而切换设置焦点，影响遥控器文本编辑。

## 实际修复

- 父 `LazyColumn` 的上下导航只对**未编辑状态**生效；编辑器真实获得焦点（`configInputFocused`）后，父级不处理 Up/Down，由文本框和电视输入法自己决定如何使用这些按键。
- `LaunchedEffect(screen, settingsTvBoxOnly, configInputFocused)` 在进入编辑状态时取消之前未完成的设置页焦点滚动，并清空 M4.49 的待处理导航目标，不让旧目标稍后把焦点抢走。
- 新增 `CompactSettingsKeyPolicy` 作为纯、可测试的输入所有权判定：仅设置页/紧凑模式/未编辑/KeyDown 的 Up/Down 才由列表处理。
- 三组 JVM 测试：浏览时上下操作保留，编辑时四个方向键均归文本框，KeyUp/非设置页/OK 等不被父级截获。
- 现有 Android TV 的“按 OK 才进入编辑框”、编辑器返回导航，以及 M4.49 的 7 次无等待连续上下按键测试继续运行。

## 限制

- 对编辑器的方向键“不截获”不等于保证某个厂商 IME 一定保留光标；Android TV 不同输入法有自己的遥控器焦点策略。此修复只保证 HERRIN 的父级设置列表不会先抢走该事件。
- 不主动弹出 TV IME，不改现有 Back 返回导航的行为；不会让方向键触发应用影视源或直播源。
- 本 HEAD Android Debug/JVM/Lint + Android TV Emulator 全量通过才能认为自动验收成功。保持 Draft，不合并主分支。

版本 `0.4.51-alpha` / versionCode 56。
