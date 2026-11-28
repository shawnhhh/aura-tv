#!/usr/bin/env python3
"""Read disposable emulator logs in memory; export public error types only."""
import json
import re
import subprocess
import os
from pathlib import Path

KINDS = ('NoSuchMethodError', 'NoSuchFieldError', 'NoClassDefFoundError',
         'IncompatibleClassChangeError', 'UnsatisfiedLinkError', 'ClassCastException',
         'SSLHandshakeException', 'SSLPeerUnverifiedException',
         'UnknownHostException', 'SocketTimeoutException', 'JSONException')


def classify(raw, sdk_classes):
    pids = set(re.findall(r'Start proc (\d+):tv\.herrin\.app:spider(?:/|\s)', raw))
    pids.update(re.findall(r'Process: tv\.herrin\.app:spider, PID: (\d+)', raw))
    rows = {}
    for line in raw.splitlines():
        match = re.match(r'\d\d-\d\d\s+\S+\s+(\d+)\s+\d+\s+[VDIWEF]\s+[^:]+:\s*(.*)', line)
        if not match or match[1] not in pids:
            continue
        message = match[2]
        for kind in KINDS:
            if not re.match(r'(?:Caused by:\s*)?(?:java\.(?:lang|net)|javax\.net\.ssl|org\.json)\.' + kind + r'(?::|\s|$)', message):
                continue
            # Only class names from the checked-in SDK inventory. No arbitrary
            # identifiers/method messages, plugin bodies, URLs or credentials.
            classes = tuple(sorted(c for c in sdk_classes if c in message))
            key = kind, classes
            rows[key] = rows.get(key, 0) + 1
    return {'scope': 'PUBLIC_ERROR_TYPES_FROM_SPIDER_LOG_ONLY',
            'matched_processes': len(pids),
            'coverage': 'PROCESS_MATCHED' if pids else 'NO_PROCESS_START_RECORD',
            'errors': [{'type': kind, 'sdk_classes': list(classes), 'count': count}
                       for (kind, classes), count in sorted(rows.items())]}


def collect(physical=False):
    if physical:
        return dict(scope='OWN_APPLICATION_REPORTS_ONLY_NO_GLOBAL_LOGCAT', errors=[],
                    coverage='GLOBAL_LOGCAT_INTENTIONALLY_NOT_READ_ON_REAL_DEVICE')
    captured = subprocess.run(['adb', 'logcat', '-d', '-t', '6000', '-v', 'threadtime',
                               '-b', 'main', '-b', 'system'], capture_output=True,
                              text=True, timeout=15)
    sdk = Path('android-tv/app/src/main/java/com/github/catvod')
    names = {'com.github.catvod.' + '.'.join(p.relative_to(sdk).with_suffix('').parts)
             for p in sdk.rglob('*.java')}
    return classify(captured.stdout, names)


if __name__ == '__main__':
    print(json.dumps(collect(os.environ.get('LIVE_PHYSICAL_DEVICE') == 'true'), indent=2))
