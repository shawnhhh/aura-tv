"""Unmodified official FongMi UI reference. Never a Herrin playback/auth PASS.

Public UI + documented /action setting/search only. No DB injection, resigning,
native hooks, private media endpoints, broad-source search or video selection.
UI XML stays in memory; no QR image/content or raw logcat is published.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import time
from urllib.parse import urlencode
from urllib.request import urlopen
from urllib.error import HTTPError, URLError
import xml.etree.ElementTree as ET

PACKAGE = 'com.fongmi.android.tv'
TARGETS = ('奶酪', '玩偶哥哥')
WAREHOUSE = 'http://www.饭太硬.net/tv'
report = dict(scope='OFFICIAL_REFERENCE_SEARCH_UI_ONLY_NO_MEDIA', herrin_acceptance=False,
              verdict='REFERENCE_BLOCKED', release='FongMi/Release 5.8.7', warehouse=WAREHOUSE,
              targets=[], stages=[])
stage = 'install'

def adb(*args, timeout=15, check=True):
    p = subprocess.run(['adb', *args], capture_output=True, text=True, timeout=timeout)
    if check and p.returncode: raise RuntimeError('ADB_COMMAND_FAILED')
    return p.stdout

def key(code):
    adb('shell', 'input', 'keyevent', str(code))

def ui():
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/p0-reference.xml', timeout=15)
    data = adb('exec-out', 'cat', '/data/local/tmp/p0-reference.xml')
    nodes=list(ET.fromstring(data).iter('node'))
    known={'vod','vod_home','title','positive','negative','search','select','cancel','mode','text',
           'permission_allow_button','permission_allow_foreground_only_button','button1','button2'}
    report['last_ui_shape'] = dict(known_controls=sorted({n.get('resource-id','').split('/')[-1]
        for n in nodes if n.get('resource-id','').split('/')[-1] in known}),
        known_labels=sorted({n.get('text') for n in nodes if n.get('text') in
            {'设置','設定','Setting','Settings','允许','允許','Allow','确定','確定','同意'}}))
    return nodes

def reset_site_scroll():
    recycler=find(ui(),rid='recycler')
    if recycler is None:raise RuntimeError('REFERENCE_SITE_LIST_MISSING')
    b=list(map(int,re.findall(r'\d+',recycler.get('bounds',''))))
    if len(b)!=4:raise RuntimeError('REFERENCE_SITE_LIST_NO_BOUNDS')
    x=(b[0]+b[2])//2
    for _ in range(10):
        adb('shell','input','swipe',str(x),str(b[1]+30),str(x),str(b[3]-30),'100')

def tap(node):
    b = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    if len(b) != 4 or b[2] <= b[0] or b[3] <= b[1]: raise RuntimeError('UI_TARGET_HAS_NO_BOUNDS')
    adb('shell', 'input', 'tap', str((b[0]+b[2])//2), str((b[1]+b[3])//2))

def find(nodes, *, rid=None, texts=(), contains=None):
    for n in nodes:
        if n.get('package') not in ('', None, PACKAGE):continue
        if rid and n.get('resource-id') == PACKAGE+':id/'+rid: return n
        if texts and n.get('text') in texts: return n
        if contains and contains in n.get('text', ''): return n
    return None

def auth(nodes):
    return any(any(w in n.get('text', '') for w in ('扫码登录', '掃碼登入', '扫描二维码', '請掃描', '网盘登录')) for n in nodes)

def click(*, rid=None, texts=(), contains=None, scroll=False, budget=18):
    until = time.monotonic()+budget
    while time.monotonic() < until:
        nodes = ui()
        if auth(nodes): raise RuntimeError('REFERENCE_AUTH_UI_REQUIRES_PERSONAL_ACTION_UNVERIFIED')
        n = find(nodes, rid=rid, texts=texts, contains=contains)
        if n is not None:
            tap(n)
            return
        if scroll:
            recycler = find(nodes, rid='recycler')
            if recycler is not None:
                b = list(map(int, re.findall(r'\d+', recycler.get('bounds', ''))))
                if len(b) == 4:
                    x=(b[0]+b[2])//2
                    adb('shell','input','swipe',str(x),str(b[3]-30),str(x),str(b[1]+30),'250')
        time.sleep(.4)
    raise RuntimeError('REFERENCE_UI_TARGET_NOT_REACHED')

def action(kind, **values):
    # Only FongMi's documented local UI handoff; never Spider/media callbacks.
    with urlopen('http://127.0.0.1:19978/action?'+urlencode(dict(do=kind, **values)), timeout=5) as reply:
        if reply.status != 200: raise RuntimeError('REFERENCE_LOCAL_API_HTTP_ERROR')
        reply.read(1024)

def mark(name):
    global stage
    stage=name
    report['stages'].append(dict(stage=name, at_ms=round((time.monotonic()-begin)*1000)))

begin=time.monotonic()
try:
    apk=Path('reference-apk/official.apk')
    digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    if digest != 'f633039fc94bac9897c5933c61a18aabfde3c1e95ab16b26796c84b9a35439d9':
        raise RuntimeError('OFFICIAL_DIGEST_MISMATCH')
    report['apk_sha256']=digest
    report['apk_modified_or_resigned']=False
    adb('install','--abi','armeabi-v7a','-r',str(apk),timeout=90)
    info=adb('shell','dumpsys','package',PACKAGE)
    report['package_primary_abi']=next((x.split('=',1)[1].strip() for x in info.splitlines() if 'primaryCpuAbi=' in x),'unknown')
    report['device_abis']=adb('shell','getprop','ro.product.cpu.abilist').strip()
    if report['package_primary_abi']!='armeabi-v7a':raise RuntimeError('EXPECTED_STANDARD_ARMV7_INSTALL')
    adb('shell','monkey','-p',PACKAGE,'-c','android.intent.category.LAUNCHER','1')
    adb('forward','tcp:19978','tcp:9978')
    mark('open_settings')
    # Upstream leanback/res/values/strings.xml names home_setting "Setting".
    click(texts=('设置','設定','Setting','Settings'),budget=25)
    click(rid='vod')
    mark('real_warehouse_configuration')
    action('setting',text=WAREHOUSE,name='P0 official reference')
    click(rid='positive')
    time.sleep(5)
    mark('restrict_search_to_two_authorized_nodes')
    click(rid='vod_home',budget=25)
    click(rid='search')
    click(rid='cancel')  # Official SiteAdapter.cancelAll(), not dialog Back.
    for name in TARGETS:
        click(contains=name,scroll=True,budget=28)
        # Return the official list to its start before finding the next target.
        reset_site_scroll()
    report['search_scope_set_via_official_ui']=list(TARGETS)
    key(4)
    key(4)
    mark('target_only_search')
    action('search',word='小城良方')
    time.sleep(10)
    for name in TARGETS:
        row=dict(node=name, verdict='REFERENCE_NOT_OBSERVED', playback_pass=False)
        report['targets'].append(row)
        start=time.monotonic()
        try:
            click(contains=name,budget=15)
            time.sleep(5)
            nodes=ui()
            if auth(nodes):
                row['verdict']='REFERENCE_AUTH_UI_UNVERIFIED'
                key(4)
            else:
                row['authorized_title_visible']=any('小城良方' in n.get('text','') for n in nodes)
                row['verdict']='REFERENCE_TITLE_VISIBLE' if row['authorized_title_visible'] else 'REFERENCE_NO_VISIBLE_MATCH_NOT_NO_RESOURCES'
            # Never click a movie: year/source identity and media are not verified here.
        except Exception as error:
            row['failure_code']=str(error) if isinstance(error,RuntimeError) else type(error).__name__
        finally: row['elapsed_ms']=round((time.monotonic()-start)*1000)
    report['verdict']='REFERENCE_SEARCH_UI_OBSERVED_NOT_HERRIN_PASS'
except Exception as error:
    report['failure_stage']=stage
    report['failure_code']=str(error) if isinstance(error,RuntimeError) else type(error).__name__
    if isinstance(error,HTTPError):report['http_status']=error.code
finally:
    report['elapsed_ms']=round((time.monotonic()-begin)*1000)
    try:report['official_app_process_alive']=bool(adb('shell','pidof',PACKAGE,check=False).strip())
    except Exception:pass
    try:adb('shell','rm','-f','/data/local/tmp/p0-reference.xml',check=False)
    except Exception:pass
    Path('reference-evidence').mkdir(exist_ok=True)
    Path('reference-evidence/reference.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))
