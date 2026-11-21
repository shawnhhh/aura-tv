#!/usr/bin/env bash
set -euo pipefail
# Disposable CI host only: authenticated read-only shares of original fixtures, bound to loopback.
# Optional unpacked Debian Samba tree supports workspace-only cloud hosts.
runner=()
fixture_user=herrinfixture
samba_root="${HERRIN_SAMBA_ROOT:-}"
if [[ -n "$samba_root" ]]; then
  export LD_LIBRARY_PATH="$samba_root/usr/lib/x86_64-linux-gnu:$samba_root/usr/lib/x86_64-linux-gnu/samba${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  smbd_bin="$samba_root/usr/sbin/smbd"
  smbpasswd_bin="$samba_root/usr/bin/smbpasswd"
  fixture_user=$(id -un)
else
  if ! command -v smbd >/dev/null 2>&1 && [[ ! -x /usr/sbin/smbd ]]; then
    sudo apt-get update -qq
    sudo apt-get install -y --no-install-recommends samba
  fi
  runner=(sudo)
  smbd_bin=/usr/sbin/smbd
  smbpasswd_bin=$(command -v smbpasswd)
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
if [[ -z "$samba_root" ]] && ! id herrinfixture >/dev/null 2>&1; then
  sudo useradd --system --no-create-home --shell /usr/sbin/nologin herrinfixture
fi
printf '%s = herrinfixture\n' "$fixture_user" > "$fixture_root/users.map"
for variant in 2 3; do
  mkdir -p "$fixture_root/state$variant" "$fixture_root/run$variant" "$fixture_root/private$variant" "$fixture_root/cache$variant" "$fixture_root/log$variant"
  if [[ "$variant" == 3 ]]; then minimum=SMB3_00; maximum=SMB3_11; port=1445; encryption=required; else minimum=SMB2_02; maximum=SMB2_10; port=1446; encryption=desired; fi
  cat > "$fixture_root/smb$variant.conf" <<CONFIG
[global]
server role = standalone server
security = user
map to guest = Never
username map = $fixture_root/users.map
interfaces = 127.0.0.1
bind interfaces only = yes
smb ports = $port
server min protocol = $minimum
server max protocol = $maximum
server signing = mandatory
smb encrypt = $encryption
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
valid users = $fixture_user
follow symlinks = no
wide links = no
CONFIG
  if [[ -n "$samba_root" ]]; then
    # IPC state stays inside the writable fixture tree.
    sed -i "/\[global\]/a ncalrpc dir = $fixture_root/run$variant" "$fixture_root/smb$variant.conf"
  fi
 done
# Public disposable fixture account only, never a user credential.
if [[ -n "$samba_root" ]]; then
  python3 - "$fixture_root/auth/smbpasswd" "$fixture_user" <<'PYPASS'
from pathlib import Path
import os, subprocess, sys, time
# Standard Samba fixture passdb; the server performs real NTLM authentication.
nt = subprocess.check_output(['openssl','dgst','-md4','-provider','legacy','-binary'],
    input='OriginalFixtureOnly123'.encode('utf-16le')).hex().upper()
p=Path(sys.argv[1]); p.write_text(f'{sys.argv[2]}:{os.getuid()}:{"X"*32}:{nt}:[U          ]:LCT-{int(time.time()):08X}:\n')
p.chmod(0o600)
PYPASS
else
  printf '%s\n%s\n' 'OriginalFixtureOnly123' 'OriginalFixtureOnly123' | "${runner[@]}" "$smbpasswd_bin" -s -a -c "$fixture_root/smb3.conf" "$fixture_user"
fi
for variant in 2 3; do "${runner[@]}" "$smbd_bin" --log-basename="$fixture_root/log$variant" --configfile="$fixture_root/smb$variant.conf" --daemon --no-process-group; done
python3 - <<'PY'
import socket,time
for port in (1445,1446):
    for attempt in range(30):
        try:
            with socket.create_connection(('127.0.0.1',port),timeout=.5): break
        except OSError: time.sleep(.2)
    else: raise RuntimeError('Controlled SMB fixture did not listen')
print('SMB fixture ready: authenticated read-only encryption-required SMB3 on 1445, SMB2 on 1446, original MP4 and sparse >5GiB file')
PY
"$smbd_bin" --version
