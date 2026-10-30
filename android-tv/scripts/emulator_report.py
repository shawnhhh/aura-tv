"""Summarize actual JUnit results, process status and fatal logs; never infer UI passes from build success."""
import base64
import re
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from remote_perf_report import parse_remote_performance, render_markdown
out = Path('build/emulator-evidence')
tests = []
for path in Path('app/build/outputs/androidTest-results').rglob('TEST-*.xml'):
    for case in ET.parse(path).getroot().iter('testcase'):
        error = case.find('failure')
        if error is None:
            error = case.find('error')
        tests.append({'class': case.get('classname'), 'test': case.get('name'),
                      'status': 'failed' if error is not None else 'skipped' if case.find('skipped') is not None else 'passed',
                      'failure': (error.get('message') or (error.text or '')[:2500]) if error is not None else None})
log = (out / 'logcat.txt').read_text(errors='replace')
# Reassemble actual Compose semantics exported in bounded logcat chunks.
chunks = {}
for label, index, total, content in re.findall(r'TREE (\S+) (\d+)/(\d+) ([A-Za-z0-9+/=]+)', log):
    chunks.setdefault(label, {})[int(index)] = (int(total), content)
for label, parts in chunks.items():
    total = next(iter(parts.values()))[0]
    if len(parts) == total:
        dest = out / 'test-evidence' / (label + '-semantics.txt')
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(base64.b64decode(''.join(parts[i][1] for i in range(total))))
summary = {'exit_code': int(sys.argv[1]), 'device': 'Android TV API 30 x86 / tv_1080p',
           'instrumentation': tests, 'fatal_exception_present': 'FATAL EXCEPTION' in log,
           'physical_TCL_tested': False,
           'decoder_evidence': re.findall(r'HerrinDecoder[^\n]*', log),
           'catalog_evidence': re.findall(r'HerrinCatalog[^\n]*', log),
           'remote_HTTPS_stream_tested': False}
# Instrumentation also writes two bounded numeric-only lines to shell-owned
# evidence so a long integration suite cannot roll the metrics out of logcat.
metric_log = log
for metric_file in (
    out / 'test-evidence' / 'remote-performance.log',
    out / 'test-evidence' / 'herrin-test-evidence' / 'remote-performance.log',
):
    if metric_file.is_file():
        metric_log += '\\n' + '\\n'.join(
            'HerrinPerf: ' + line
            for line in metric_file.read_text(errors='replace').splitlines()
        )
# Only whitelisted numeric key/value pairs go into artifacts.
summary['remote_performance'] = parse_remote_performance(metric_log)
(out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
lines = ['# Actual Android TV emulator results', '', f"Script exit: {summary['exit_code']}", '',
         '| Instrumentation test | Result |', '| --- | --- |']
lines += [f"| {t['test']} | {t['status']} |" for t in tests]
lines += ['', *render_markdown(summary['remote_performance']), '',
          'ADB smoke: see adb-smoke.log and launch.txt. Startup frames are real emulator screenshots.',
          'Physical TCL remote, TV IME variants, 4K overscan and real streaming remain unverified.']
(out / 'REPORT.md').write_text('\n'.join(lines) + '\n')
print(json.dumps(summary, ensure_ascii=False))
# Fixed ORIGINAL synthetic fixture only; never export authorization QR/user media.
# Full-resolution originals remain artifacts. This small actual-compositor thumbnail
# allows visual review using the GitHub logs connector without an artifact host route.
frames = {}
for index, total, data in re.findall(r'FRAME m437-mp4-visible-frame (\d+)/(\d+) ([A-Za-z0-9+/=]+)', log):
    frames[int(index)] = (int(total), data)
if frames and len(frames) == next(iter(frames.values()))[0]:
    encoded = ''.join(frames[i][1] for i in range(1, len(frames) + 1))
    if len(encoded) <= 180_000:
        (out / 'original-mp4-compositor-thumbnail.jpg').write_bytes(base64.b64decode(encoded))
        print('HERRIN_ORIGINAL_VIDEO_FRAME_BASE64=' + encoded)
