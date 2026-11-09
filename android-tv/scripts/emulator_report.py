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
        metric_log += '\n' + '\n'.join(
            'HerrinPerf: ' + line
            for line in metric_file.read_text(errors='replace').splitlines()
        )
# Only whitelisted numeric key/value pairs go into artifacts.
summary['remote_performance'] = parse_remote_performance(metric_log)
startup_log = out / 'startup-smoke.log'
startup_source = startup_log.read_text(errors='replace') if startup_log.exists() else ''
startup_fields = ('process_to_activity_ms', 'activity_to_first_draw_ms', 'first_draw_to_wordmark_ms',
                  'wordmark_complete_ms', 'activity_to_home_ms', 'home_init_ms', 'artwork_start_ms',
                  'network_start_ms', 'system_brand_exit_ms')
summary['startup_samples'] = [
    {key: int(value) for key, value in re.findall(r'([a-z_]+)=(-?\d+)', line) if key in startup_fields}
    for line in startup_source.splitlines() if 'process_to_activity_ms=' in line
]
summary['startup_phases'] = [
    {'phase': phase, 'activity_to_phase_ms': int(value)}
    for phase, value in re.findall(r'HerrinStartupPhase[^\n]*phase=(home_init|artwork|network|system_brand_exit|first_cpu_draw|wordmark_visible|wordmark_complete) activity_to_phase_ms=(\d+)', startup_source)
]
summary['startup_window_probes'] = []
for payload in re.findall(r'HerrinStartupProbe[^\n]*?(\{[^\n]+\})', log):
    probe = json.loads(payload)
    summary['startup_window_probes'].append({
        'version': probe['version'], 'refresh_hz': probe['refresh_hz'],
        'warm_process_activity_start': True, 'physical_tcl': False,
        **{stage: {field: probe[stage][field] for field in ('count', 'p50_ms', 'p95_ms', 'p99_ms', 'over_budget')}
           for stage in ('reveal', 'breathing', 'handoff') if stage in probe}
    })
summary['source_recovery_evidence'] = re.findall(r'HerrinSourceRecovery[^\n]*', log)
summary['source_feedback_evidence'] = re.findall(r'HerrinSourceFeedback[^\n]*', log)
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

# Test-controlled offline exit dialog; never export phone pairing QR/service secrets.
exit_parts = {}
for index, total, data in re.findall(r'EXIT_FRAME (\d+)/(\d+) ([A-Za-z0-9+/=]+)', log):
    exit_parts[int(index)] = (int(total), data)
if exit_parts and len(exit_parts) == next(iter(exit_parts.values()))[0]:
    encoded = ''.join(exit_parts[i][1] for i in range(1, len(exit_parts)+1))
    if len(encoded) <= 180000:
        (out / 'exit-confirmation-preview.jpg').write_bytes(base64.b64decode(encoded))
        print('HERRIN_EXIT_FRAME_BASE64=' + encoded)
