"""Emit a factual Chinese CI acceptance report from JUnit files and artifact hashes."""
from pathlib import Path
import hashlib
import os
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
out = root / 'build/acceptance'
out.mkdir(parents=True, exist_ok=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
run = f"https://github.com/{os.getenv('GITHUB_REPOSITORY', 'shawnhhh/aura-tv')}/actions/runs/{os.getenv('GITHUB_RUN_ID', 'local')}"
lines = ['# HERRIN M4.37 自动验收记录', '', f'- 构建提交：`{sha}`', f'- CI：{run}',
         '- 基线：`2509da39a83bdca0b5cfc1fe2ac3a37e67b3d9da`（PR #43）', '',
         '| 测试组 | 总数 | 失败 | 错误 | 跳过 |', '| --- | ---: | ---: | ---: | ---: |']
for name, folder in [('JVM', 'app/build/test-results/testDebugUnitTest'),
                     ('Android TV', 'app/build/outputs/androidTest-results')]:
    counts = [0, 0, 0, 0]
    for path in (root / folder).rglob('TEST-*.xml'):
        for case in ET.parse(path).getroot().iter('testcase'):
            counts[0] += 1
            for i, tag in enumerate(('failure', 'error', 'skipped'), 1):
                counts[i] += case.find(tag) is not None
    lines.append(f"| {name} | {' | '.join(map(str, counts))} |")
lines += ['', '当前作业未执行的测试组显示 0；完整结论须联合 Debug 和 Emulator 两个作业。', '',
          '## 可安装 APK', '', '在 Debug 工作流的 Artifacts 中下载 `HERRIN-debug-apk`，解压后通过 Android TV 安装或 `adb install -r app-debug.apk` 安装。']
for apk in (root / 'app/build/outputs/apk/debug').glob('*.apk'):
    lines.append(f'- `{apk.name}`：{apk.stat().st_size} 字节；SHA-256 `{hashlib.sha256(apk.read_bytes()).hexdigest()}`')
lines += ['', '## 验收边界', '',
          '- 出帧、遥控 seek、焦点、MP4/HLS/DASH 和画面像素证据见 Emulator Artifacts 中的测试报告、截图及 summary.json。',
          '- 授权仅使用原创模拟服务；未验收第三方网盘账号和具体私有影视源。',
          '- 弹幕仅在用户开启并使用插件明确提供的协议地址时加载；故障不阻断播放。',
          '- TMDB 使用模拟响应自动回归；线上刮削需用户合法 Read Access Token。',
          '- 未验收 TCL T7L Ultra 真机、4K 输出、Dolby Vision、HDR10+、电视音响或平台 DRM。',
          '- 所有功能、限制及手工验收步骤见仓库 docs/M4_37_CONSUMER_TV_ACCEPTANCE_ZH.md。']
(out / '验收报告.md').write_text('\n'.join(lines) + '\n')
print('\n'.join(lines))
