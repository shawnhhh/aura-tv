#!/usr/bin/env python3
"""Read only public method declarations from the verified runtime JAR; never execute DEX."""
import glob
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

reports = []
for name in ('wanou', 'cheese'):
    text = subprocess.run(['adb', 'exec-out', 'run-as', 'tv.herrin.app', 'cat',
                           f'files/live-node-{name}.json'], capture_output=True).stdout
    try:
        report = json.loads(text)
        digest = report.get('declared_jar_md5', '')
        if not re.fullmatch('[a-fA-F0-9]{32}', digest):
            continue
        payload = subprocess.run(['adb', 'exec-out', 'run-as', 'tv.herrin.app', 'cat',
                                  f'code_cache/herrin-spider/{digest.lower()}.jar'],
                                 capture_output=True, check=True).stdout
        # The formal loader has already checked this exact MD5. Recheck before metadata inspection.
        import hashlib
        if hashlib.md5(payload).hexdigest() != digest.lower():
            raise ValueError('jar_hash_changed')
        tools = sorted(glob.glob(os.environ['ANDROID_HOME'] + '/build-tools/*/dexdump'))
        if not tools:
            raise ValueError('dexdump_unavailable')
        with tempfile.TemporaryDirectory() as tmp:
            jar = Path(tmp, 'provider.jar')
            jar.write_bytes(payload)
            classes = {}
            with zipfile.ZipFile(jar) as archive:
                for item in archive.infolist():
                    if not re.fullmatch(r'classes\d*\.dex', item.filename):
                        continue
                    dex = Path(tmp, item.filename)
                    dex.write_bytes(archive.read(item))
                    result = subprocess.run([tools[-1], '-l', 'xml', str(dex)],
                                            capture_output=True, text=True, check=True)
                    xml = result.stdout[result.stdout.find('<api'):]
                    root = ET.fromstring(xml)
                    for package in root.findall('package'):
                        for klass in package.findall('class'):
                            owner = package.get('name') + '.' + klass.get('name')
                            methods = []
                            for method in klass.findall('method'):
                                if method.get('name') == 'searchContent':
                                    methods.append({'name': 'searchContent',
                                                    'parameters': [x.get('type') for x in method.findall('parameter')],
                                                    'return': method.get('return'),
                                                    'native': method.get('native')})
                            classes[owner] = {'parent': klass.get('extends'), 'methods': methods}
            owner = 'com.github.catvod.spider.' + report['spider_api'].removeprefix('csp_')
            chain = []
            for _ in range(8):
                found = classes.get(owner)
                if found is None:
                    break
                chain.append({'class': owner, **found})
                owner = found['parent']
            reports.append({'node': report['node'], 'jar_md5': digest, 'declarations': chain})
    except Exception as error:
        reports.append({'node': name, 'metadata_error': type(error).__name__})
Path('evidence/spider-methods.json').write_text(json.dumps(reports, ensure_ascii=False, indent=2))
print('PUBLIC_SPIDER_METHOD_DECLARATIONS ' + json.dumps(reports, ensure_ascii=False))
