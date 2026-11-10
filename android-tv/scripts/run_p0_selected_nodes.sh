#!/usr/bin/env bash
set -euo pipefail
if [[ "${LIVE_NODES:-}" != both ]]; then
    exec bash android-tv/scripts/run_live_nodes.sh
fi
# One diagnostic APK build and emulator, two independent genuine node tests.
# Preserve a failed node verdict while allowing the other node to finish.
result=0
LIVE_NODE=cheese bash android-tv/scripts/run_live_nodes.sh || result=1
LIVE_NODE=wanou bash android-tv/scripts/run_live_nodes.sh || result=1
exit "$result"
