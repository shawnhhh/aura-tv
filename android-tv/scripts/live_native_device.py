#!/usr/bin/env python3
"""Exact-SHA diagnostic artifacts and opt-in disposable native ARM playback.

The default 'prepare' command is read-only on the device. Only the explicitly
named 'run-on-disposable-device' command installs APKs and runs the existing
MainActivity driver, which resets THIS test application's preferences.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

BRANCH = 'codex/herrin-p0-live-playback-20261110'
APKS = ('app-debug.apk', 'app-debug-androidTest.apk')
MACHINES = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40)}
ROOT = Path(__file__).resolve().parents[2]


def apk_metadata(path, abi):
    libraries = []
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if not name.startswith('lib/') or not name.endswith('.so'):
                continue
            parts = name.split('/')
            if len(parts) != 3 or parts[1] != abi:
                raise ValueError('APK_CONTAINS_OTHER_ABI')
            with archive.open(name) as stream:
                header = stream.read(20)
            if len(header) < 20 or header[:4] != b'\x7fELF' or header[5] != 1:
                raise ValueError('NATIVE_LIBRARY_INVALID_ELF')
            elf = (header[4], int.from_bytes(header[18:20], 'little'))
            if elf != MACHINES[abi]:
                raise ValueError('NATIVE_LIBRARY_MACHINE_MISMATCH')
            libraries.append(dict(member=name, elf_class=elf[0], machine=elf[1]))
    return dict(sha256=hashlib.sha256(path.read_bytes()).hexdigest(), bytes=path.stat().st_size,
                native_libraries=sorted(libraries, key=lambda item: item['member']))


def manifest(directory, sha, abi):
    if not re.fullmatch('[0-9a-f]{40}', sha):
        raise ValueError('FULL_SOURCE_COMMIT_SHA_REQUIRED')
    if abi not in MACHINES:
        raise ValueError('SUPPORTED_ARM_ABI_REQUIRED')
    data = dict(schema=1, source_sha=sha, source_branch=BRANCH, abi=abi,
                scope='INTERNAL_DIAGNOSTIC_NOT_PLAYBACK_ACCEPTANCE',
                files={name: apk_metadata(directory/name, abi) for name in APKS})
    if not data['files'][APKS[0]]['native_libraries']:
        raise ValueError('ARM_APPLICATION_NATIVE_LIBRARIES_MISSING')
    return data


def verify(directory, sha):
    data = json.loads((directory/'diagnostic-manifest.json').read_text())
    if data.get('source_sha') != sha or data.get('source_branch') != BRANCH:
        raise ValueError('DIAGNOSTIC_SOURCE_SHA_OR_BRANCH_MISMATCH')
    if data.get('abi') != 'arm64-v8a':
        raise ValueError('NATIVE_ARM64_DIAGNOSTIC_REQUIRED')
    if data != manifest(directory, sha, data['abi']):
        raise ValueError('DIAGNOSTIC_ARTIFACT_HASH_OR_ELF_MISMATCH')
    return data


def device_preflight(adb):
    def prop(name):
        return adb('shell', 'getprop', name).strip()
    abi = prop('ro.product.cpu.abi')
    abis = prop('ro.product.cpu.abilist').split(',')
    qemu = prop('ro.kernel.qemu')
    if abi != 'arm64-v8a' or 'arm64-v8a' not in abis or any('x86' in value for value in abis) or qemu == '1':
        raise ValueError('PHYSICAL_NATIVE_ARM64_REQUIRED_NO_TRANSLATION_RETRY')
    sdk = prop('ro.build.version.sdk')
    if not sdk.isdigit() or int(sdk) < 26:
        raise ValueError('ANDROID_API_26_OR_NEWER_REQUIRED')
    if prop('sys.boot_completed') != '1':
        raise ValueError('DEVICE_NOT_BOOTED')
    bridge = prop('ro.dalvik.vm.native.bridge')
    return dict(primary_abi=abi, supported_abis=abis, sdk=int(sdk), qemu=qemu,
                native_bridge=bridge if re.fullmatch(r'[A-Za-z0-9_.-]{0,80}', bridge) else 'REDACTED',
                root_used=False, other_app_data_read=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('manifest', 'prepare', 'run-on-disposable-device'))
    parser.add_argument('--artifact', type=Path, required=True)
    parser.add_argument('--sha', required=True)
    parser.add_argument('--abi', choices=tuple(MACHINES), default='arm64-v8a')
    parser.add_argument('--serial')
    parser.add_argument('--node', choices=('cheese', 'wanou'), default='cheese')
    parser.add_argument('--film', choices=('shinchan', 'xiaocheng'), default='shinchan')
    parser.add_argument('--evidence', type=Path)
    args = parser.parse_args()
    directory = args.artifact.resolve()
    if args.command == 'manifest':
        data = manifest(directory, args.sha, args.abi)
        (directory/'diagnostic-manifest.json').write_text(json.dumps(data, indent=2)+'\n')
        print(json.dumps(data, indent=2))
        return
    data = verify(directory, args.sha)
    if directory.name != 'arm64-v8a':
        raise ValueError('KEEP_THE_ARTIFACT_ARM64_V8A_DIRECTORY_LAYOUT')
    if not args.serial:
        parser.error('--serial is required to explicitly select the test device')
    if not shutil.which('adb'):
        raise ValueError('DEVICE_OPERATOR_PLATFORM_TOOLS_REQUIRED')
    def adb(*values):
        result = subprocess.run(['adb', '-s', args.serial, *values], capture_output=True, text=True, timeout=20)
        if result.returncode:
            raise ValueError('SCOPED_ADB_PREFLIGHT_COMMAND_FAILED')
        return result.stdout
    observation = device_preflight(adb)
    output = (args.evidence or ROOT/'native-device-evidence'/f'{args.sha[:12]}-{args.node}-{args.film}').resolve()
    output.mkdir(parents=True, exist_ok=True)
    record = dict(source_sha=args.sha, artifact=data, device=observation,
                  scope='NATIVE_DEVICE_PREPARATION_NOT_PLAYBACK_PASS', app_install_performed=False,
                  test_executed=False, daily_configuration_cleared=False)
    (output/'native-device-preflight.json').write_text(json.dumps(record, indent=2)+'\n')
    print(json.dumps(record, indent=2))
    if args.command == 'prepare':
        print('PREPARED_READ_ONLY: no install, settings reset, login or playback performed')
        return
    actual_sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if actual_sha != args.sha:
        raise ValueError('DEVICE_DRIVER_CHECKOUT_MUST_MATCH_ARTIFACT_SHA')
    work = ROOT/'evidence'
    if work.exists() and any(work.iterdir()):
        raise ValueError('MOVE_PRIOR_REPOSITORY_EVIDENCE_BEFORE_NEW_DEVICE_RUN')
    env = dict(os.environ, ANDROID_SERIAL=args.serial, LIVE_NODE=args.node,
               LIVE_FILM_TITLE='蜡笔小新' if args.film == 'shinchan' else '小城良方',
               ARM_PACKAGE='true', LIVE_INSTALL_ABI='arm64-v8a', LIVE_PREBUILT_APKS=str(directory.parent),
               LIVE_MAIN_UI='true', LIVE_DISPOSABLE_DEVICE='true', LIVE_METHOD_METADATA='false',
               LIVE_REQUIRE_ACCEPTANCE='true', LIVE_PHYSICAL_DEVICE='true')
    print('DISPOSABLE_TEST_APP_ONLY: the existing MainActivity driver resets tv.herrin.app test preferences')
    result = subprocess.run(['bash', 'android-tv/scripts/run_live_nodes.sh'], cwd=ROOT, env=env)
    allowed = {f'{args.node}.json', f'native-crash-summary-{args.node}.json',
               f'spider-error-shapes-{args.node}.json', 'system-abilist.txt', 'native-bridge.txt',
               'package-abi.txt', 'instrumentation.txt', 'install.txt'}
    for path in work.iterdir() if work.exists() else ():
        if path.name in allowed and path.is_file():
            shutil.copy2(path, output/path.name)
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    try:
        main()
    except ValueError as error:
        raise SystemExit(str(error)) from None
