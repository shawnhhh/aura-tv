"""Same runner/AVD ABBA observations, never physical-TV FPS certification."""
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path('candidate/android-tv/scripts').resolve()))
from remote_perf_report import parse_remote_performance

root=pathlib.Path('performance-ab')
legs={leg:parse_remote_performance('\n'.join('HerrinPerf: '+line for line in (root/(leg+'-metrics.txt')).read_text().splitlines())) for leg in ('A1','B1','B2','A2')}
for leg, metrics in legs.items():
    assert all(key in metrics for key in ('horizontal','vertical','burst','frame_baseline')), leg
    assert metrics['horizontal']['focus_success']==40 and metrics['vertical']['focus_success']==32
    assert metrics['burst']['events']==120 and metrics['burst']['last_focus_correct']==1
# No addresses, credentials, arbitrary app messages or framebuffer contents in the console summary.
display=(root/'display.txt').read_text()
summary={'baseline':'2af99374ab44052bb8ab6c76daeed5e94d75bd3d',
         'candidate':'c4a05606338992f66f684e8e0c261179b58bc671',
         'device':'one Android TV API30 x86 tv_1080p AVD, SwiftShader',
         'order':['A1','B1','B2','A2'],'tests_per_leg':5,'physical_TCL_tested':False,
         'display_refresh_hz':sorted(set(re.findall(r'(?:fps=|refreshRate[= ])([0-9.]+)',display))),
         'observations':legs}
(root/'summary.json').write_text(json.dumps(summary,indent=2))
print('HERRIN_ABBA='+json.dumps(summary))
