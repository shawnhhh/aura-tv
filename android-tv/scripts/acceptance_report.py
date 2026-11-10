import os
"""Emit a factual Chinese CI acceptance report from JUnit files and artifact hashes."""
from pathlib import Path
import hashlib
import re
import json
from remote_perf_report import render_markdown
import os
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
out = root / 'build/acceptance'
out.mkdir(parents=True, exist_ok=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
run = f"https://github.com/{os.getenv('GITHUB_REPOSITORY', 'shawnhhh/aura-tv')}/actions/runs/{os.getenv('GITHUB_RUN_ID', 'local')}"
gradle_text = (root / 'app/build.gradle.kts').read_text()
version_match = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', gradle_text, re.M)
version_label = version_match.group(1) if version_match else '版本未识别'
lines = [f'# HERRIN {version_label} 自动验收记录', '', f'- 构建提交：`{sha}`', f'- CI：{run}',
         '- 结论：必须同时确认本提交的 Debug 与 Android TV Emulator 两项工作流均成功。', '',
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
# Concrete stage-three-to-five cases, with actual JUnit outcomes rather than build inference.
groups = {
    'HTTPS/证书/配对': {'LanConfigIdentityPolicyTest','LanConfigurationTest','LanHttpConfigurationTest','LanTlsIdentityTest','P0QrFailureTest','P0ExitAndEntryTest'},
    '启动显现/呼吸/过渡': {'WordmarkMotionTest','M439LaunchMotionPolicyTest','HerrinSplashTest','P0StartupFrameProbeTest'},
    'SMB策略/真实协议/加密/Seek/解码': {'SmbPolicyTest','SmbM2tsHandleTest','SmbProtocolIntegrationTest','SmbConfigSecurityTest','SmbRealPlaybackTest'},
}
lines += ['', '## 第三至第五阶段实际用例回执', '', '| 专项 | 本作业执行 | 失败/错误 | 跳过 |', '| --- | ---: | ---: | ---: |']
for label, classes in groups.items():
    cases=[]
    for folder in ('app/build/test-results/testDebugUnitTest','app/build/outputs/androidTest-results'):
        for path in (root/folder).rglob('TEST-*.xml'):
            cases += [case for case in ET.parse(path).getroot().iter('testcase')
                if (case.get('classname') or '').rsplit('.',1)[-1] in classes]
    failed=sum(case.find('failure') is not None or case.find('error') is not None for case in cases)
    skipped=sum(case.find('skipped') is not None for case in cases)
    lines.append(f'| {label} | {len(cases)} | {failed} | {skipped} |')
lines += ['', 'SMB 正向用例连接受控、认证、签名的真实 Samba SMB2/SMB3 服务；稀疏 5GiB 文件只证明偏移读取，不能冒充真实 5GiB MKV 解码或 4K 吞吐。',
          '启动 1800ms 显现 + 2400ms 同步呼吸 + 340ms 淡出是正常动画设计；减少动态/用户明确跳过例外。Compose 驱动的 ABBA 不能冒充冷启动实际动画时长。',
          'TLS 自签名身份持久化不等于 Safari 自动信任。真实 TCL / iPhone / 家庭 NAS / 第三方私有节点仍待真机验收。']
lines += ['', '当前作业未执行的测试组显示 0；完整结论须联合 Debug 和 Emulator 两个作业。', '',
          '## APK 工件', '',
          ('本P0分支真实节点尚未通过，CI不上传APK；临时安装包仅用于本轮诊断，不是验收交付。'
           if (os.environ.get('GITHUB_HEAD_REF') or os.environ.get('GITHUB_REF_NAME')) in
               {'codex/herrin-global-search-20261107','codex/herrin-p0-live-playback-20261110'}
           else '在 Debug 工作流的 Artifacts 中下载 `HERRIN-debug-apk`，解压后通过 Android TV 安装或 `adb install -r app-debug.apk` 安装。')]
controlled=[]
for path in (root / 'app/build/test-results/testDebugUnitTest').rglob('TEST-*.xml'):
    for node in ET.parse(path).getroot().iter('system-out'):
        for raw in (node.text or '').splitlines():
            if raw.startswith('HERRIN_CONTROLLED_SOURCE='):
                controlled.append(json.loads(raw.partition('=')[2]))
if controlled:
    metrics={}
    for name in ('search_callback_ms','candidate_ms','verified_ms','total_ms'):
        data=sorted(row[name] for row in controlled)
        metrics[name]={f'p{q}':round(data[(len(data)-1)*q//100],3) for q in (50,95,99)}
    record={'scope':'controlled_loopback_not_live_provider','samples':controlled,'percentiles_ms':metrics}
    (out/'controlled-source.json').write_text(json.dumps(record,indent=2))
    print('HERRIN_CONTROLLED_SOURCE_SUMMARY='+json.dumps(record))
    lines+=['','## 可控 HTTP 媒体验证实验','','仅为本机 MockWebServer 与原创 MP4 字节校验，不能代表真实影视源速度、成功率或详情页绘制延迟。','',
            '```json',json.dumps(metrics,ensure_ascii=False,indent=2),'```']
for apk in (root / 'app/build/outputs/apk/debug').glob('*.apk'):
    lines.append(f'- `{apk.name}`：{apk.stat().st_size} 字节；SHA-256 `{hashlib.sha256(apk.read_bytes()).hexdigest()}`')
performance_summary = root / 'build/emulator-evidence/summary.json'
if performance_summary.is_file():
    try:
        performance = json.loads(performance_summary.read_text()).get('remote_performance', {})
        lines += ['', *render_markdown(performance)]
    except (ValueError, OSError, TypeError):
        lines += ['', '## Android TV 遥控器性能', '', '指标文件无法解析；不能声称已采样或达标。']
full_suite_gate = root / 'build/emulator-evidence/full-suite-gate.log'
if full_suite_gate.exists():
    lines += ['', '## 完整 Android TV 用例覆盖门禁', '',
              '```text', full_suite_gate.read_text(errors='replace').strip(), '```']
lines += ['', '## 验收边界', '',
          '- 出帧、遥控 seek、焦点、MP4/HLS/DASH 和画面像素证据见 Emulator Artifacts 中的测试报告、截图及 summary.json。',
          '- 授权仅使用原创模拟服务；未验收第三方网盘账号和具体私有影视源。',
          '- 弹幕仅在用户开启并使用插件明确提供的协议地址时加载；故障不阻断播放。',
          '- TMDB 使用模拟响应自动回归；线上刮削需用户合法 Read Access Token。',
          '- SMB2/3 由CI受控Samba真实协议验证，原媒体解码和Seek以SmbRealPlaybackTest实际JUnit/logcat为准；不代表用户NAS吞吐。',
          '- 白字逐帧图片见wordmark-*.png；Android12启动另见同提交API31工作流视频/时序，不以录屏作为性能来源。',
          '- 未验收 TCL T7L Ultra 真机、4K 输出、Dolby Vision、HDR10+、电视音响或平台 DRM。',
          '- 所有功能、限制及手工验收步骤见仓库 docs/M4_37_CONSUMER_TV_ACCEPTANCE_ZH.md。']
(out / '验收报告.md').write_text('\n'.join(lines) + '\n')
print('\n'.join(lines))
