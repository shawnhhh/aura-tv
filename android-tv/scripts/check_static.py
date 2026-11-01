"""Offline structural checks; does not substitute for compilation or remote testing."""
from pathlib import Path
import hashlib
import re
import zipfile
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
for path in root.glob('app/src/main/**/*.xml'):
    ET.parse(path)
# XML validity alone misses process names rejected by Android's package parser.
for path in root.glob('app/src/*/AndroidManifest.xml'):
    for element in ET.parse(path).getroot().iter():
        process = element.get('{http://schemas.android.com/apk/res/android}process')
        if process is not None:
            assert re.fullmatch(r':?[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*', process), (path, process)
jar = root / 'gradle/wrapper/gradle-wrapper.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest() == '498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17'
with zipfile.ZipFile(jar) as z:
    assert z.testzip() is None
    assert 'org/gradle/wrapper/GradleWrapperMain.class' in z.namelist()
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
assert 'LEANBACK_LAUNCHER' in manifest and 'touchscreen' in manifest
assert all((root / p).is_file() for p in ['gradlew', 'gradlew.bat', 'settings.gradle.kts', 'app/build.gradle.kts'])
# The playback-only FongMi browser resolver is explicitly audited.
# Keep all other production Kotlin modules free of embedded browsers.
browser_backend = root / 'app/src/main/java/tv/herrin/app/FongMiBrowserPlayback.kt'
assert browser_backend.is_file()
browser_code = browser_backend.read_text()
assert 'WebViewClient' in browser_code and 'javaScriptEnabled = true' in browser_code
assert 'addJavascriptInterface' not in browser_code and 'handler.proceed()' not in browser_code
assert 'allowFileAccess = false' in browser_code and 'allowContentAccess = false' in browser_code
spider_runtime = root / 'app/src/main/java/tv/herrin/app/TvBoxSpiderRemote.kt'
spider_code = spider_runtime.read_text()
assert spider_code.count('WebView') == 1 and 'WebView.setDataDirectorySuffix' in spider_code
assert not any('WebView' in p.read_text() for p in root.glob('app/src/main/java/**/*.kt')
               if p.resolve() not in {browser_backend.resolve(), spider_runtime.resolve()})
print('PASS: XML, TV manifest, official wrapper integrity, Gradle files, native-only source')
