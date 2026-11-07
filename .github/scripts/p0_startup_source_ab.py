"""Fixed observer, same AVD ABBA; no optical input or TCL display measurements."""
import json
import os
from pathlib import Path
import re
import sys
sys.path.insert(0,str(Path('android-tv/scripts').resolve()))
from remote_perf_report import parse_remote_performance

root=Path('startup-ab')
observations={}
for leg in ('A1','B1','B2','A2'):
    log=(root/f'{leg}-logcat.txt').read_text()
    result=(root/f'{leg}-instrumentation.txt').read_text()
    assert 'OK (6 tests)' in result and 'INSTRUMENTATION_CODE: -1' in result,leg
    remote=parse_remote_performance(log)
    assert all(name in remote for name in ('horizontal','vertical','burst','frame_baseline')),leg
    assert remote['horizontal']['focus_success']==40 and remote['vertical']['focus_success']==32,leg
    assert remote['burst']['events']==120 and remote['burst']['last_focus_correct']==1,leg
    startup=[json.loads(line[line.index('{'):]) for line in log.splitlines() if 'HerrinStartupProbe:' in line and '{' in line]
    assert len(startup)==1,(leg,startup)
    cold=(root/f'{leg}-cold-launch.txt').read_text()
    values={name:int(value) for name,value in re.findall(r'^(ThisTime|TotalTime|WaitTime): (\d+)',cold,re.M)}
    observations[leg]={'startup_window_frames':startup[0],'remote':remote,'am_start_W_ms':values}
summary={'baseline_sha':os.environ['BASELINE_SHA'],'candidate_sha':os.environ['CANDIDATE_SHA'],
    'scope':'one Android TV API30 x86 1920x1080 SwiftShader AVD',
    'order':['A1','B1','B2','A2'],'tests_per_leg':6,'physical_tcl':False,
    'startup_observer':'warm-process Activity start; FrameMetrics total duration over 0-1799 and 1800-3500ms after first intended VSync',
    'black_screen_optically_measured':False,'observations':observations}
(root/'summary.json').write_text(json.dumps(summary,indent=2))
print('HERRIN_STARTUP_ABBA='+json.dumps(summary))
