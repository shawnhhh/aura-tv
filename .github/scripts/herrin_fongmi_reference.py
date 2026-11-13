"""Unmodified official FongMi UI reference. Never a Herrin playback/auth PASS.

Public UI + documented /action setting/search only. No DB injection, resigning,
native hooks, private media endpoints, broad-source search or media URL injection.
UI XML is temporary on the disposable device and removed; no QR image/content
or raw logcat is published.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import time
from urllib.parse import urlencode
from urllib.request import urlopen
from urllib.error import HTTPError, URLError
import xml.etree.ElementTree as ET

PACKAGE = 'com.fongmi.android.tv'
TV_REFERENCE = os.environ.get('REFERENCE_SCENARIO') == 'cheese_tv'
WANOU_HOME = os.environ.get('REFERENCE_WANOU_HOME') == 'true'
REFERENCE_TITLE = '蜡笔小新第九季' if TV_REFERENCE else '小城良方'
REFERENCE_ABI = 'arm64-v8a' if TV_REFERENCE else 'armeabi-v7a'
REFERENCE_DIGEST = 'cda5776ba2ee6f7dfdcd3d7c1a13095ba8672b9892d6fb9e1ba38f993ad66f3a' if TV_REFERENCE else 'f633039fc94bac9897c5933c61a18aabfde3c1e95ab16b26796c84b9a35439d9'
TARGETS = ('玩偶',) if WANOU_HOME else (('奶酪',) if TV_REFERENCE else ('奶酪', '玩偶'))
WAREHOUSE = 'http://www.饭太硬.net/tv'
report = dict(scope='OFFICIAL_REFERENCE_UI_AND_PLAYER_ERROR_OBSERVATION_NO_FRAME_ACCEPTANCE', herrin_acceptance=False,
              verdict='REFERENCE_BLOCKED', release='FongMi/Release 5.8.7', warehouse=WAREHOUSE,
              targets=[], stages=[])
stage = 'install'
parents = {}

def adb(*args, timeout=15, check=True):
    p = subprocess.run(['adb', *args], capture_output=True, text=True, timeout=timeout)
    if check and p.returncode: raise RuntimeError('ADB_COMMAND_FAILED')
    return p.stdout

def key(code):
    adb('shell', 'input', 'keyevent', str(code))

def ui():
    global parents
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/p0-reference.xml', timeout=15)
    data = adb('exec-out', 'cat', '/data/local/tmp/p0-reference.xml')
    root=ET.fromstring(data)
    parents={child:parent for parent in root.iter() for child in parent}
    nodes=list(root.iter('node'))
    known={'vod','vodHome','vodUrl','title','positive','negative','search','select','cancel','mode','text',
           'permission_allow_button','permission_allow_foreground_only_button','button1','button2'}
    report['last_ui_shape'] = dict(known_controls=sorted({n.get('resource-id','').split('/')[-1]
        for n in nodes if n.get('resource-id','').split('/')[-1] in known}),
        known_labels=sorted({n.get('text') for n in nodes if n.get('text') in
            {'设置','設定','Setting','Settings','允许','允許','Allow','确定','確定','同意'}}),
        known_focused_controls=sorted({n.get('resource-id','').split('/')[-1] for n in nodes
            if n.get('focused')=='true' and n.get('resource-id','').split('/')[-1] in known}))
    return nodes

def reset_site_scroll():
    recycler=find(ui(),rid='recycler')
    if recycler is None:raise RuntimeError('REFERENCE_SITE_LIST_MISSING')
    b=list(map(int,re.findall(r'\d+',recycler.get('bounds',''))))
    if len(b)!=4:raise RuntimeError('REFERENCE_SITE_LIST_NO_BOUNDS')
    x=(b[0]+b[2])//2
    for _ in range(10):
        adb('shell','input','swipe',str(x),str(b[1]+30),str(x),str(b[3]-30),'100')

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
            # Use the real TV/keyboard focus path. Touch coordinates on leanback
            # cards/buttons may only focus them; never double-activate toggles.
            target=n
            while target.get('focusable')!='true' and target in parents:
                target=parents[target]
            if target.get('focusable')!='true':raise RuntimeError('REFERENCE_CONTROL_NOT_FOCUSABLE')
            if any(child.get('focused')=='true' for child in target.iter()):
                key(23)
                return
            key(61)  # Standard keyboard focus-forward, no app-private API.
            continue
        if scroll:
            recycler = find(nodes, rid='recycler')
            if recycler is not None:
                b = list(map(int, re.findall(r'\d+', recycler.get('bounds', ''))))
                if len(b) == 4:
                    x=(b[0]+b[2])//2
                    adb('shell','input','swipe',str(x),str(b[3]-30),str(x),str(b[1]+30),'250')
        time.sleep(.4)
    raise RuntimeError('REFERENCE_UI_TARGET_NOT_REACHED')

def tap_node(node):
    # Public Android input only. TAB traversal had no focused control in the
    # actual SiteDialog log; use its real clickable view's measured bounds.
    target = node
    while target.get('clickable') != 'true' and target in parents:
        target = parents[target]
    if target.get('clickable') != 'true':
        raise RuntimeError('REFERENCE_CONTROL_NOT_CLICKABLE')
    bounds = list(map(int, re.findall(r'\d+', target.get('bounds', ''))))
    if len(bounds) != 4 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
        raise RuntimeError('REFERENCE_CONTROL_INVALID_BOUNDS')
    adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))


def activate_focused(node):
    target = node
    while target.get('clickable') != 'true' and target in parents:
        target = parents[target]
    if not any(n.get('focused') == 'true' for n in target.iter()):
        raise RuntimeError('REFERENCE_TARGET_NOT_FOCUSED_AFTER_TOUCH')
    key(23)


def search_mode():
    nodes = ui()
    button = find(nodes, rid='search')
    if button is None:
        raise RuntimeError('REFERENCE_SEARCH_MODE_MISSING')
    if button.get('selected') != 'true':
        tap_node(button)
    button = find(ui(), rid='search')
    if button is not None and button.get('selected') != 'true':
        # Actual run a91f84cf: the first touch focused this leanback button
        # without toggling it. Activate ONLY after observing that exact state.
        activate_focused(button)
        button = find(ui(), rid='search')
    if button is None or button.get('selected') != 'true':
        raise RuntimeError('REFERENCE_SEARCH_MODE_NOT_SELECTED')


def site_rows(nodes):
    rows = []
    for node in nodes:
        if node.get('resource-id') != PACKAGE+':id/text':
            continue
        parent = parents.get(node)
        while parent is not None:
            checks = [n for n in parent.iter('node') if n.get('resource-id') == PACKAGE+':id/check']
            if checks:
                if len(checks) == 1:
                    rows.append((node.get('text', ''), checks[0].get('checked') == 'true', node))
                break
            parent = parents.get(parent)
    return rows


def scroll_sites(nodes):
    recycler = find(nodes, rid='recycler')
    if recycler is None:
        raise RuntimeError('REFERENCE_SITE_LIST_MISSING')
    b = list(map(int, re.findall(r'\d+', recycler.get('bounds', ''))))
    if len(b) != 4:
        raise RuntimeError('REFERENCE_SITE_LIST_NO_BOUNDS')
    x = (b[0]+b[2])//2
    adb('shell', 'input', 'swipe', str(x), str(b[3]-30), str(x), str(b[1]+30), '250')


def scan_sites(enable_targets=False):
    reset_site_scroll()
    found = {}
    previous = None
    for _ in range(40):
        nodes = ui()
        rows = site_rows(nodes)
        signature = tuple(name for name, _, _ in rows)
        if not signature:
            raise RuntimeError('REFERENCE_SITE_CHECKBOXES_NOT_OBSERVED')
        changed = False
        for name, checked, node in rows:
            wanted = any(target in name for target in TARGETS)
            if enable_targets and wanted and not checked:
                tap_node(node)
                current_rows = [(n, c, v) for n, c, v in site_rows(ui()) if n == name]
                if len(current_rows) == 1 and not current_rows[0][1]:
                    activate_focused(current_rows[0][2])
                    current_rows = [(n, c, v) for n, c, v in site_rows(ui()) if n == name]
                current = [(n, c) for n, c, _ in current_rows]
                if current != [(name, True)]:
                    raise RuntimeError('REFERENCE_TARGET_CHECK_NOT_CONFIRMED')
                found[name] = True
                changed = True
                break  # ui() replaced the parent map; reacquire remaining rows.
            found[name] = checked
        if changed:
            previous = None
            continue
        if signature == previous:
            return found
        previous = signature
        scroll_sites(nodes)
    raise RuntimeError('REFERENCE_SITE_LIST_SCAN_INCOMPLETE')


def restrict_search():
    search_mode()
    cancel = find(ui(), rid='cancel')
    if cancel is None:
        raise RuntimeError('REFERENCE_CANCEL_ALL_MISSING')
    tap_node(cancel)
    nodes = ui()
    if any(checked for _, checked, _ in site_rows(nodes)):
        cancel = find(nodes, rid='cancel')
        if cancel is None:
            raise RuntimeError('REFERENCE_CANCEL_ALL_MISSING')
        activate_focused(cancel)
    disabled = scan_sites()
    if len(disabled) <= 2 or any(disabled.values()):
        raise RuntimeError('REFERENCE_CANCEL_ALL_NOT_CONFIRMED')
    selected = scan_sites(enable_targets=True)
    enabled = [name for name, value in selected.items() if value]
    if len(enabled) != len(TARGETS) or not all(sum(target in name for name in enabled) == 1 for target in TARGETS):
        raise RuntimeError('REFERENCE_EXACT_TWO_SITE_SCOPE_NOT_CONFIRMED')
    report['search_scope_set_via_official_ui'] = list(TARGETS)
    report['site_checkboxes_observed'] = len(selected)
    report['scope_verified_by_checkboxes'] = True


def observe_wanou_default_home():
    click(rid='vodHome',budget=25)
    reset_site_scroll()
    for _ in range(40):
        nodes = ui()
        target = find(nodes, contains='玩偶')
        if target is not None:
            tap_node(target)
            nodes = ui()
            if find(nodes, rid='search') is not None:
                target = find(nodes, contains='玩偶')
                if target is None:
                    raise RuntimeError('REFERENCE_DEFAULT_TARGET_LOST')
                activate_focused(target)
            break
        scroll_sites(nodes)
    else:
        raise RuntimeError('REFERENCE_DEFAULT_WANOU_NOT_FOUND')
    # vodHome is an ImageView in the pinned original layout, not a text label.
    # Reopen the genuine site chooser and verify SiteAdapter's selected text.
    click(rid='vodHome',budget=25)
    nodes = ui()
    selected = find(nodes, contains='玩偶')
    report['default_wanou_setting_verified'] = selected is not None and selected.get('selected') == 'true'
    key(4)
    if not report['default_wanou_setting_verified']:
        raise RuntimeError('REFERENCE_DEFAULT_WANOU_NOT_CONFIRMED')
    key(4)
    time.sleep(8)
    nodes = ui()
    report['default_wanou_home_observed'] = True
    report['public_authorization_ui_observed'] = auth(nodes)
    if report['public_authorization_ui_observed']:
        key(4)  # No screenshots, QR data, credential handling or claimed auth pass.
        report['authorization_back_sent'] = True
    click(texts=('设置','設定','Setting','Settings'),budget=25)


def observe_original_player(row):
    # Exact authorized title only, on the already restricted Cheese result.
    # The unmodified client's own detail/player owns parsing and playback.
    adb('logcat', '-c')
    click(texts=(REFERENCE_TITLE,), budget=15)
    time.sleep(5)
    nodes = ui()
    row['detail_year_2026_visible'] = any(re.search(r'(?<![0-9])2026(?![0-9])', n.get('text', '')) for n in nodes)
    row['detail_title_visible'] = any(REFERENCE_TITLE in n.get('text', '') for n in nodes)
    row['requested_source_title'] = REFERENCE_TITLE
    if (not TV_REFERENCE and not row['detail_year_2026_visible']) or not row['detail_title_visible']:
        key(4)
        row['player_observation'] = 'REFERENCE_DETAIL_IDENTITY_NOT_VERIFIED'
        return
    time.sleep(20)  # Observe this one original playback attempt, no retries initiated by this script.
    pids = set(adb('shell', 'pidof', PACKAGE, check=False).split())
    crash_raw = adb('logcat', '-b', 'crash', '-d', '-t', '600', timeout=15)
    spec = importlib.util.spec_from_file_location('crash_metadata', Path('android-tv/scripts/live_native_crash.py'))
    classifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(classifier)
    row['native_crash_metadata'] = classifier.classify(crash_raw, PACKAGE)
    raw = adb('logcat', '-d', '-t', '6000', '-v', 'threadtime', timeout=15)
    scoped = []
    for line in raw.splitlines():
        match = re.match(r'\d\d-\d\d\s+\S+\s+(\d+)\s+\d+\s+[VDIWEF]\s+[^:]+:\s*(.*)', line)
        if match and match[1] in pids:
            scoped.append(match[2])
    # Never publish raw lines, addresses, headers, exception messages or screenshots.
    codes = {}
    patterns = {
        'HTTP_403': r'(?:Response code:|responseCode[=:])\s*403\b',
        'HTTP_502': r'(?:Response code:|responseCode[=:])\s*502\b',
        'HLS_HEADER_INVALID': r'Input does not start with the #EXTM3U header',
        'DNS_ERROR': r'UnknownHostException',
        'TLS_ERROR': r'SSLHandshakeException|SSLPeerUnverifiedException',
        'IO_TIMEOUT': r'SocketTimeoutException',
        'PARSER_EXCEPTION': r'ParserException',
        'PLAYER_EXCEPTION': r'ExoPlaybackException',
    }
    for code, pattern in patterns.items():
        count = sum(bool(re.search(pattern, line)) for line in scoped)
        if count:
            codes[code] = count
    row['original_player_error_types'] = codes
    row['original_player_log_scope'] = 'MATCHED_PACKAGE_PID_ONLY' if pids else 'NO_PID_OBSERVED'
    row['original_player_initialized_log_observed'] = any('ExoPlayerImpl' in line and 'Init ' in line for line in scoped)
    row['player_observation'] = 'ORIGINAL_MEDIA_ATTEMPT_ERRORS_OBSERVED' if codes else 'ORIGINAL_MEDIA_ATTEMPT_NO_CLASSIFIED_ERROR'
    row['playback_pass'] = False
    row['first_frame_verified'] = False
    key(4)


def action(kind, **values):
    # Only FongMi's documented local UI handoff; never Spider/media callbacks.
    with urlopen('http://127.0.0.1:19978/action?'+urlencode(dict(do=kind, **values)), timeout=5) as reply:
        if reply.status != 200: raise RuntimeError('REFERENCE_LOCAL_API_HTTP_ERROR')
        reply.read(1024)

def mark(name):
    global stage
    stage=name
    report['stages'].append(dict(stage=name, at_ms=round((time.monotonic()-begin)*1000)))

def main():
    global begin
    begin=time.monotonic()
    try:
        apk=Path('reference-apk/official.apk')
        digest=hashlib.sha256(apk.read_bytes()).hexdigest()
        if digest != REFERENCE_DIGEST:
            raise RuntimeError('OFFICIAL_DIGEST_MISMATCH')
        report['apk_sha256']=digest
        report['apk_modified_or_resigned']=False
        adb('install','--abi',REFERENCE_ABI,'-r',str(apk),timeout=90)
        info=adb('shell','dumpsys','package',PACKAGE)
        report['package_primary_abi']=next((x.split('=',1)[1].strip() for x in info.splitlines() if 'primaryCpuAbi=' in x),'unknown')
        report['device_abis']=adb('shell','getprop','ro.product.cpu.abilist').strip()
        if report['package_primary_abi']!=REFERENCE_ABI:raise RuntimeError('EXPECTED_STANDARD_ABI_INSTALL')
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
        if WANOU_HOME:
            mark('original_default_wanou_home')
            observe_wanou_default_home()
        mark('restrict_search_to_authorized_nodes')
        click(rid='vodHome',budget=25)
        restrict_search()
        key(4)
        key(4)
        mark('target_only_search')
        action('search',word=REFERENCE_TITLE)
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
                    row['authorized_title_visible']=any(REFERENCE_TITLE in n.get('text','') for n in nodes)
                    row['verdict']='REFERENCE_TITLE_VISIBLE' if row['authorized_title_visible'] else 'REFERENCE_NO_VISIBLE_MATCH_NOT_NO_RESOURCES'
                    if name == '奶酪' and row['authorized_title_visible']:
                        mark('cheese_original_detail_player_observation')
                        observe_original_player(row)
                # Error observations do not establish decoded frames or playback acceptance.
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
    


if __name__ == "__main__":
    main()
