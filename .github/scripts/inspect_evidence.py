"""Inspect a previously retained failure without starting or retrying any emulator tests."""
import base64
from pathlib import Path
root = Path('evidence')
for p in root.rglob('*'):
    if p.is_file() and p.suffix in ('.png', '.xml', '.json', '.txt'):
        print('EVIDENCE_FILE', p.relative_to(root))
for pattern in ['**/FAIL-*.png', '**/search.png', '**/home.png', '**/splash-HERRIN.png']:
    for p in root.glob(pattern):
        if p.is_file():
            print('HERRIN_PNG', p.name, base64.b64encode(p.read_bytes()).decode())
for p in root.rglob('logcat.txt'):
    lines = p.read_text(errors='replace').splitlines()
    print('SEARCH_LOGCAT', '\n'.join(x for x in lines if any(s in x for s in ['InputMethod', 'ImeTracker', 'HerrinUiSmokeTest', 'tv.herrin.app'] ))[-22000:])
