#!/usr/bin/env bash
set -euo pipefail
# Disposable CI host only: authenticated read-only shares of original fixtures, bound to loopback.
if ! command -v smbd >/dev/null 2>&1 && [[ ! -x /usr/sbin/smbd ]]; then
  sudo apt-get update -qq
  sudo apt-get install -y --no-install-recommends samba
fi
fixture_root="${HERRIN_SMB_FIXTURE_ROOT:-/tmp/herrin-smb-fixture}"
mkdir -p "$fixture_root/data" "$fixture_root/auth"
cp android-tv/app/src/androidTest/assets/synthetic.mp4 "$fixture_root/data/Original SMB.2026.mp4"
cp android-tv/app/src/androidTest/assets/synthetic.mp4 "$fixture_root/data/Original Series.S01E01.mp4"
cp android-tv/app/src/androidTest/assets/m467/synthetic.mkv "$fixture_root/data/Original MKV.2026.mkv"
cp android-tv/app/src/androidTest/assets/synthetic-segment-00.ts "$fixture_root/data/Original TS.2026.ts"
python3 - "$fixture_root/data" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
with (root/'Large.2026.mkv').open('wb') as f:
    f.write(b'HERRIN-START')
    f.seek(5*1024**3)
    f.write(b'HERRIN-LARGE-SEEK-END')
transport=Path('android-tv/app/src/androidTest/assets/synthetic-segment-00.ts').read_bytes()
assert len(transport)%188==0
with (root/'Original M2TS.2026.m2ts').open('wb') as f:
    for offset in range(0,len(transport),188):
        assert transport[offset]==0x47
        f.write(((offset//188)*300).to_bytes(4,'big'))
        f.write(transport[offset:offset+188])
(root/'not-media.txt').write_text('Not a media file')
(root/'nested').mkdir(exist_ok=True)
PY
chmod 755 "$fixture_root" "$fixture_root/data"
chmod 644 "$fixture_root/data/"*.*
if ! id herrinfixture >/dev/null 2>&1; then sudo useradd --system --no-create-home --shell /usr/sbin/nologin herrinfixture; fi
for variant in 2 3; do
  mkdir -p "$fixture_root/state$variant" "$fixture_root/run$variant" "$fixture_root/private$variant" "$fixture_root/cache$variant"
  if [[ "$variant" == 3 ]]; then minimum=SMB3_00; maximum=SMB3_11; port=1445; else minimum=SMB2_02; maximum=SMB2_10; port=1446; fi
  cat > "$fixture_root/smb$variant.conf" <<CONFIG
[global]
server role = standalone server
security = user
map to guest = Never
interfaces = 127.0.0.1
bind interfaces only = yes
smb ports = $port
server min protocol = $minimum
server max protocol = $maximum
server signing = mandatory
smb encrypt = desired
passdb backend = smbpasswd:$fixture_root/auth/smbpasswd
state directory = $fixture_root/state$variant
private dir = $fixture_root/private$variant
lock directory = $fixture_root/run$variant
pid directory = $fixture_root/run$variant
cache directory = $fixture_root/cache$variant
log file = $fixture_root/smb$variant.log
load printers = no
printing = bsd
disable spoolss = yes
[media]
path = $fixture_root/data
read only = yes
guest ok = no
valid users = herrinfixture
follow symlinks = no
wide links = no
CONFIG
 done
# Public disposable fixture account only, never a user credential.
printf '%s\n%s\n' 'OriginalFixtureOnly123' 'OriginalFixtureOnly123' | sudo smbpasswd -s -a -c "$fixture_root/smb3.conf" herrinfixture
for variant in 2 3; do sudo /usr/sbin/smbd --configfile="$fixture_root/smb$variant.conf" --daemon --no-process-group; done
python3 - <<'PY'
import socket,time
for port in (1445,1446):
    for attempt in range(30):
        try:
            with socket.create_connection(('127.0.0.1',port),timeout=.5): break
        except OSError: time.sleep(.2)
    else: raise RuntimeError('Controlled SMB fixture did not listen')
print('SMB fixture ready: authenticated read-only SMB3 on 1445, SMB2 on 1446, original MP4 and sparse >5GiB file')
PY
/usr/sbin/smbd --version
