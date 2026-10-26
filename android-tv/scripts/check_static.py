"""Offline structural checks; does not substitute for compilation or remote testing."""
from pathlib import Path
import hashlib
import zipfile
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
for path in root.glob('app/src/main/**/*.xml'):
    ET.parse(path)
jar = root / 'gradle/wrapper/gradle-wrapper.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest() == '498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17'
with zipfile.ZipFile(jar) as z:
    assert z.testzip() is None
    assert 'org/gradle/wrapper/GradleWrapperMain.class' in z.namelist()
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
assert 'LEANBACK_LAUNCHER' in manifest and 'touchscreen' in manifest
assert all((root / p).is_file() for p in ['gradlew', 'gradlew.bat', 'settings.gradle.kts', 'app/build.gradle.kts'])
assert not any('WebView' in p.read_text() for p in root.glob('app/src/main/java/**/*.kt'))
print('PASS: XML, TV manifest, official wrapper integrity, Gradle files, native-only source')
