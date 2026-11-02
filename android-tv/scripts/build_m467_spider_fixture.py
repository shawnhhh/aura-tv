"""Build the original test-only CatVod JAR using Android's actual D8 compiler.

Compile-only Spider ABI stub is excluded. No remote JAR or provider is executed.
The resulting JAR is an androidTest asset, never production app code.
"""
from pathlib import Path
import os
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ['ANDROID_HOME'])
android = sdk / 'platforms/android-35/android.jar'
d8 = sdk / 'build-tools/35.0.0/d8'
with tempfile.TemporaryDirectory() as tmp:
    tmp = Path(tmp)
    classes = tmp / 'classes'
    classes.mkdir()
    sources = sorted((root / 'test-fixtures/m467').rglob('*.java'))
    subprocess.run(['javac', '--release', '8', '-cp', str(android), '-d', str(classes), *map(str, sources)], check=True)
    plugin = sorted((classes / 'com/github/catvod/spider').rglob('*.class'))
    subprocess.run([str(d8), '--min-api', '26', '--lib', str(android), '--output', str(tmp), *map(str, plugin)], check=True)
    output = root / 'app/src/androidTest/assets/m467-spider.jar'
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as jar:
        for name, data in [('classes.dex', (tmp / 'classes.dex').read_bytes()),
                           ('m467-original.mp4', (root / 'app/src/androidTest/assets/synthetic.mp4').read_bytes())]:
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            jar.writestr(info, data)
    print(f'Built test-only DEX JAR: {output.name} ({output.stat().st_size} bytes)')
