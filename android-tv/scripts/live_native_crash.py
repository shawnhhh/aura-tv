#!/usr/bin/env python3
"""Classify Android crash-buffer metadata without exporting raw logs/addresses."""
import json
import re
import subprocess


def classify(text, package="tv.herrin.app:spider"):
    if package not in ("tv.herrin.app:spider", "com.fongmi.android.tv"):
        raise ValueError("Only the two authorized playback packages may be classified")
    result = []
    known = ('libndk_translation.so', 'libart.so', 'libc.so', 'libc++.so',
             'libandroid_runtime.so', 'libdl.so', 'linker64', 'linker')
    for section in text.split('*** *** ***'):
        if not re.search(r'>>> ' + re.escape(package) + r' <<<', section):
            continue
        signal = re.search(r'signal (\d+) \((SIG[A-Z]+)\)', section)
        frames = []
        for line in section.splitlines():
            if re.search(r'#\d+\s+pc\s+', line):
                frames.append(next((module for module in known if module in line), 'PLUGIN_OR_OTHER'))
        result.append({'process': 'spider' if package == 'tv.herrin.app:spider' else 'official_reference', 'signal': int(signal[1]) if signal else None,
                       'signal_name': signal[2] if signal else 'UNKNOWN',
                       'top_modules': frames[:12]})
    return {'native_crashes': result[-4:]}


if __name__ == '__main__':
    captured = subprocess.run(['adb', 'logcat', '-b', 'crash', '-d', '-t', '600'],
                              capture_output=True, text=True, timeout=15)
    print(json.dumps(classify(captured.stdout), indent=2))
