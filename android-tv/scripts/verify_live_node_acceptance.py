#!/usr/bin/env python3
"""Gate real playback evidence, independently of JUnit/CI collection success."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--node', choices=['cheese', 'wanou'], required=True)
parser.add_argument('--directory', default='evidence')
args = parser.parse_args()
path = Path(args.directory, args.node + '.json')
data = json.loads(path.read_text())
verdict = data.get('verdict')
if args.node == 'wanou' and verdict == 'AUTH_FLOW_PASS_PLAYBACK_PENDING':
    verified = any(s.get('qr_decoded') and s.get('cancel_pass') and s.get('back_pass') for s in data.get('stages', []))
    assert verified, 'AUTH_FLOW_PENDING lacks genuine QR/cancel/Back evidence'
    print('AUTH_FLOW_PASS_PLAYBACK_PENDING: private user action still required; not REAL_PLAYBACK_PASS')
else:
    assert verdict == 'REAL_PLAYBACK_PASS', f"REAL_PLAYBACK_NOT_ACCEPTED: {verdict}, stage={data.get('failure_stage')}, code={data.get('failure_code')}"
    assert data.get('first_frame') and data.get('seek_verified'), 'missing decoded frames or Seek'
    assert any(x.get('continuous_ms_minimum', 0) >= 60000 and x.get('frames', 0) > 3 for x in data.get('playback_evidence', [])), 'missing 60-second actual playback evidence'
    required = {'first_episode', 'replay_same_episode', 'next_episode', 'return_first_episode', 'replay_after_details'}
    assert required <= {x.get('operation') for x in data.get('playback_evidence', [])}, 'missing mandatory replay/episode/detail-return operations'
    assert data.get('returned_to_details'), 'missing real player Back/details transition'
    assert all(x.get('pause_resume_verified') and x.get('seek_verified') for x in data.get('playback_evidence', [])), 'incomplete controls evidence'
    assert not data.get('production_media_gate_failed'), 'production media validation failed'
    print('REAL_PLAYBACK_PASS: actual frames, 60 seconds, pause/resume/Seek evidence verified')
