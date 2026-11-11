"""Check declared binary build inputs; no downloads, playback or emulator.

Input lock artifacts: path, sha256, source_repository, source_commit,
version, license_file, build_instructions. Paths resolve relative to --directory.
Only explicit locked AAR files are accepted. Never extract classes from an APK.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parent
UPSTREAM = ROOT.parent / 'vendor/fongmi-core/upstream'


def verify(directory, lock_path):
    lock = json.loads(lock_path.read_text())
    artifacts = lock['artifacts']
    if not artifacts:
        raise ValueError('DEPENDENCY_BLOCKED: no source/version/license-locked external artifacts')
    names, classes, locked_upstream = set(), set(), set()
    for artifact in artifacts:
        for field in ('path', 'sha256', 'source_repository', 'source_commit', 'version',
                      'license_file', 'build_instructions'):
            if not artifact.get(field):
                raise ValueError('Missing provenance field: ' + field)
        if not re.fullmatch(r'[0-9a-f]{40}', artifact['source_commit']):
            raise ValueError('Corresponding source must use a full commit')
        base = UPSTREAM if artifact.get('origin') == 'upstream' else directory
        path = (base / artifact['path']).resolve()
        path.relative_to(base.resolve())
        if artifact.get('origin') == 'upstream':
            locked_upstream.add(artifact['path'])
        if path.suffix != '.aar' or path.name in names:
            raise ValueError('Duplicate or non-AAR input')
        names.add(path.name)
        if hashlib.sha256(path.read_bytes()).hexdigest() != artifact['sha256']:
            raise ValueError('Artifact checksum differs: ' + path.name)
        license_file = (directory / artifact['license_file']).resolve()
        license_file.relative_to(directory.resolve())
        if not license_file.read_text().strip():
            raise ValueError('Empty license material')
        with zipfile.ZipFile(path) as aar:
            with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as jar:
                found = {n[:-6] for n in jar.namelist() if n.endswith('.class')}
                duplicates = classes & found
                if duplicates:
                    raise ValueError('Competing classes in AAR set: ' + sorted(duplicates)[0])
                if not found:
                    raise ValueError('Empty implementation: ' + path.name)
                classes.update(found)
            if path.name in lock['required_external_archives']:
                for abi in ('arm64-v8a', 'armeabi-v7a'):
                    if not any(n.startswith('jni/' + abi + '/') and n.endswith('.so') for n in aar.namelist()):
                        raise ValueError('Missing native ABI ' + abi + ': ' + path.name)
    missing_archives = set(lock['required_external_archives']) - names
    if missing_archives:
        raise ValueError('Missing archives: ' + ', '.join(sorted(missing_archives)))
    missing_upstream = set(lock['required_upstream_archives']) - locked_upstream
    if missing_upstream:
        raise ValueError('Upstream binary provenance missing: ' + ', '.join(sorted(missing_upstream)))
    imports = set()
    for flavor in ('main', 'leanback'):
        for file in (UPSTREAM / 'app/src' / flavor / 'java').rglob('*.java'):
            imports.update(re.findall(r'^import (androidx\.media3\.[\w.]+);', file.read_text(), re.M))
    missing = sorted(i for i in imports if i.replace('.', '/') not in classes
                     and not any(i.replace('.', '/').startswith(c + '/') for c in classes))
    if missing:
        raise ValueError('Required original Media3 APIs absent: ' + ', '.join(missing))
    extra = {p.name for p in directory.glob('*.aar')} - names
    if extra:
        raise ValueError('Unpinned AAR inputs: ' + ', '.join(sorted(extra)))
    print(json.dumps({'status': 'BUILD_INPUTS_PRESENT_NOT_PLAYBACK_PROOF',
                      'artifacts': sorted(names), 'media3_imports_checked': len(imports)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--lock', type=Path, default=ROOT / 'dependencies.json')
    args = parser.parse_args()
    try:
        verify(args.directory, args.lock)
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(2, str(error) + '\n')
