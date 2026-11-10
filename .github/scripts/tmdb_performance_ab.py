"""TCL feedback baseline vs candidate observation on one AVD, never a TCL speed claim."""
import json
import pathlib
import re
import statistics
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path('candidate/android-tv/scripts').resolve()))
from remote_perf_report import parse_remote_performance

root = pathlib.Path('tmdb-performance-ab')
observations = {}
for leg in ('A1', 'B1', 'B2', 'A2'):
    for group, count in (('home', 1), ('remote', 5)):
        result = (root / f'{leg}-{group}.txt').read_text()
        assert f'OK ({count} test' in result and 'INSTRUMENTATION_CODE: -1' in result, (leg, group, result)
        assert 'FAILURES!!!' not in result and 'INSTRUMENTATION_FAILED' not in result, (leg, group)
    log = (root / f'{leg}-home-logcat.txt').read_text()
    switches = re.findall(r'^[ \t]*([0-9]+(?:\.[0-9]+)?)[^\n]*HerrinCachedHero[^\n]*asserting_category=', log, re.M)
    assert len(switches) == 12, (leg, switches)
    startup = re.findall(r'HerrinStartup\s*:[^\n]*process_to_activity_ms=[^\n]+', log)
    assert startup, leg
    numeric = dict((key, int(value)) for key, value in re.findall(r'([a-z_]+)=(-?\d+)', startup[-1]))
    for key in ('activity_to_first_draw_ms', 'activity_to_home_ms'):
        assert numeric[key] >= 0, (leg, key)
    remote = parse_remote_performance((root / f'{leg}-remote-logcat.txt').read_text())
    assert all(key in remote for key in ('horizontal', 'vertical', 'burst', 'frame_baseline')), leg
    assert remote['horizontal']['focus_success'] == 40 and remote['vertical']['focus_success'] == 32, leg
    assert remote['burst']['events'] == 120 and remote['burst']['last_focus_correct'] == 1, leg
    observations[leg] = {
        **numeric, 'cached_navigation_11_transitions_ms': round((float(switches[-1]) - float(switches[0])) * 1000, 2),
        'remote': remote,
    }
metrics = ('activity_to_first_draw_ms', 'activity_to_home_ms', 'cached_navigation_11_transitions_ms')
comparison = {}
for key in metrics:
    before = statistics.mean(observations[leg][key] for leg in ('A1', 'A2'))
    after = statistics.mean(observations[leg][key] for leg in ('B1', 'B2'))
    comparison[key] = {'baseline_mean': before, 'candidate_mean': after, 'delta_ms': round(after - before, 2)}
summary = {
    'baseline': subprocess.check_output(['git', '-C', 'baseline', 'rev-parse', 'HEAD'], text=True).strip(),
    'candidate': subprocess.check_output(['git', '-C', 'candidate', 'rev-parse', 'HEAD'], text=True).strip(),
    'device': 'one Android TV API30 x86 tv_1080p AVD, SwiftShader', 'order': ['A1', 'B1', 'B2', 'A2'],
    'physical_TCL_tested': False, 'tests_per_leg': 6, 'observations': observations, 'comparison_ms': comparison,
    'scope': 'Cached pages: pre-feedback 599d58d versus candidate independent rails; same Hero IDs and assertion bounds. Remote demo test sources identical. Includes test synchronization, not photon latency.',
}
(root / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
print('HERRIN_TMDB_ABBA=' + json.dumps(summary, ensure_ascii=False))
