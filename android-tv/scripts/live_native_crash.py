#!/usr/bin/env python3
"""Package-scoped native diagnostics, without raw messages, paths or memory dumps.

Keep relative PCs/build IDs for lawful offline symbolization. Registers and fault
addresses are numeric only. Never publish tombstone text, abort messages, loaded
file paths, stack memory, URLs, tokens or other applications' crash sections.
"""
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
        detailed_frames = []
        for line in section.splitlines():
            match = re.search(r'#(\d+)\s+pc\s+([0-9a-fA-F]{8,16})\s+(\S+)(.*)', line)
            if not match:
                continue
            frames.append(next((module for module in known if module in line), 'PLUGIN_OR_OTHER'))
            basename = match[3].rsplit('/', 1)[-1].rsplit('!', 1)[-1]
            # File basenames only; no application-private directory components.
            module = basename if re.fullmatch(r'(?:lib[A-Za-z0-9_.+-]{1,100}\.so|linker64|linker|base\.apk)', basename) else 'UNIDENTIFIED_MAPPING'
            frame = dict(index=int(match[1]), relative_pc='0x'+match[2].lower(), module=module)
            build_id = re.search(r'BuildId:\s*([0-9a-fA-F]{16,64})\b', match[4])
            if build_id:
                frame['build_id'] = build_id[1].lower()
            symbol = re.search(r'\(([^()]*)\)', match[4])
            if module in known and symbol and re.fullmatch(r'[A-Za-z0-9_:$<>.,+*& ~=-]{1,256}', symbol[1]):
                frame['system_symbol'] = symbol[1]
            detailed_frames.append(frame)
        row = {'process': 'spider' if package == 'tv.herrin.app:spider' else 'official_reference', 'signal': int(signal[1]) if signal else None,
               'signal_name': signal[2] if signal else 'UNKNOWN', 'top_modules': frames[:12],
               'frames': detailed_frames[:128], 'frames_truncated': len(detailed_frames) > 128}
        thread = re.search(r'pid:\s*(\d+),\s*tid:\s*(\d+),\s*name:\s*(.*?)\s+>>>', section)
        if thread:
            name = thread[3].strip()
            row.update(pid=int(thread[1]), tid=int(thread[2]), thread_name=name if re.fullmatch(
                r'(?:main|Thread-\d+|Binder:\d+_\d+|DefaultDispatcher-worker-\d+|pool-\d+-thread-\d+|OkHttp Dispatcher|Okio Watchdog)', name) else 'OTHER_REDACTED')
        abi = re.search(r"ABI:\s*'(arm64|arm|x86_64|x86)'", section)
        if abi:
            row['abi'] = abi[1]
        fault = re.search(r'fault addr\s+(0x[0-9a-fA-F]{1,16}|-+)', section)
        if fault:
            row['fault_address'] = fault[1]
        code = re.search(r'signal \d+ \(SIG[A-Z]+\), code (-?\d+)', section)
        if code:
            row['signal_code'] = int(code[1])
        registers = {}
        for line in section.splitlines():
            payload = re.sub(r'^.*?\bDEBUG\s*:\s*', '', line)
            # A backtrace's relative "pc" must never overwrite the actual PC
            # register from the tombstone register row.
            if not re.match(r'^\s*(?:x\d{1,2}|r\d{1,2}|sp|lr|pc|ip|fp|pstate|cpsr)\s+[0-9a-fA-F]{8,16}\b', payload):
                continue
            registers.update({name: '0x'+value.lower() for name, value in re.findall(
                r'(?:^|\s)(x\d{1,2}|r\d{1,2}|sp|lr|pc|ip|fp|pstate|cpsr)\s+([0-9a-fA-F]{8,16})(?=\s|$)', payload)})
        row['registers'] = registers
        row['symbolization'] = 'RELATIVE_PCS_REQUIRE_MATCHING_BUILD_ID_ELF_AND_SYMBOLS' if detailed_frames else 'NO_FRAMES_IN_CRASH_BUFFER'
        result.append(row)
    return {'native_crashes': result[-4:]}


if __name__ == '__main__':
    captured = subprocess.run(['adb', 'logcat', '-b', 'crash', '-d', '-t', '600'],
                              capture_output=True, text=True, timeout=15)
    print(json.dumps(classify(captured.stdout), indent=2))
