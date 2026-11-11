"""Inventory runtime binary ABI/provenance inputs without executing them."""
from pathlib import Path
import hashlib
import json
import struct

ROOT = Path(__file__).resolve().parent
UPSTREAM = ROOT.parent / 'vendor/fongmi-core/upstream'


def inventory():
    binaries = []
    for module in ('catvod', 'quickjs', 'nodejs', 'thunder', 'chaquo'):
        for path in sorted((UPSTREAM / module).rglob('*')):
            if not path.is_file() or path.suffix not in ('.jar', '.aar', '.so'):
                continue
            raw = path.read_bytes()
            record = {'path': str(path.relative_to(UPSTREAM)), 'sha256': hashlib.sha256(raw).hexdigest(),
                      'corresponding_source_verified': False}
            if raw[:4] == b'\x7fELF':
                machine = struct.unpack(('<' if raw[5] == 1 else '>') + 'H', raw[18:20])[0]
                record.update(elf_class=32 if raw[4] == 1 else 64,
                              machine=machine, abi={40: 'armeabi-v7a', 183: 'arm64-v8a'}.get(machine, 'OTHER'))
            binaries.append(record)
    python_lock = json.loads((UPSTREAM / 'chaquo/runtime/native.lock.json').read_text())
    return {'scope': 'BUILD_DEPENDENCY_INPUTS_NOT_PLAYBACK_OR_LICENSE_CLEARANCE',
            'tv_commit': '305dff233cde512e2c83f71529b7c3ecfd61fefc',
            'python_native_lock': python_lock, 'binaries': binaries,
            'node_cmake': '3.22.1', 'java': 21,
            'runtime_distribution': 'Original RuntimeModule companion packages, not copied into app',
            'unresolved': ['Media3 exact custom API commit', 'MPV/FFmpeg AAR corresponding build sources',
                           'QuickJS wrapper/native primary source commit', 'libnode.so and thunder .so corresponding source/license build mapping']}


if __name__ == '__main__':
    print(json.dumps(inventory(), indent=2))
