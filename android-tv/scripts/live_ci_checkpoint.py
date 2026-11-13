#!/usr/bin/env python3
"""Persist observed CI stages and invoke the existing real-playback acceptance gate.

No provider traffic, media substitution or raw logcat retention. This is evidence
collection for run_live_nodes.sh, not a second device test driver.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def read_report(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return {}


def checkpoint(directory, node):
    report = read_report(directory / (node + '.json'))
    stage = next((name for name in ('instrumentation-started', 'apks-installed',
                                   'emulator-booted', 'kvm-ready')
                  if (directory / name).exists()), 'RUNNER_SETUP')
    data = {
        'head': os.environ.get('GITHUB_SHA'),
        'run_id': os.environ.get('GITHUB_RUN_ID'),
        'automatic_push_trigger': os.environ.get('GITHUB_EVENT_NAME') == 'push',
        'node': node,
        'kvm_ready': (directory / 'kvm-ready').exists(),
        'emulator_booted': (directory / 'emulator-booted').exists(),
        'apks_installed': (directory / 'apks-installed').exists(),
        'instrumentation_started': (directory / 'instrumentation-started').exists(),
        'last_infrastructure_stage': stage,
        'provider_report_present': bool(report),
        'provider_verdict': report.get('verdict'),
        'provider_failure_stage': report.get('failure_stage'),
        'provider_failure_code': report.get('failure_code'),
        'first_frame': report.get('first_frame', False),
        'seek_verified': report.get('seek_verified', False),
        'next_condition': ('Inspect the first failed provider stage and compare original behavior; '
                           'change code, network or legitimate authorization before another request.'
                           if report else 'Restore the first missing CI stage shown by markers/job logs; no provider result exists.'),
    }
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'environment-checkpoint.json').write_text(json.dumps(data, indent=2) + '\n')
    # A report alone does not establish that the instrumentation completed.
    output = (directory / 'instrumentation.txt')
    text = output.read_text() if output.exists() else ''
    completed = 'OK (1 test)' in text and not any(x in text for x in
                ('INSTRUMENTATION_FAILED', 'FAILURES!!!'))
    passed = completed and data['apks_installed'] and report.get('verdict') == 'REAL_PLAYBACK_PASS'
    suite = ET.Element('testsuite', name='RealMainActivity.' + node,
                       tests='1', failures=str(int(not passed)), errors='0', skipped='0')
    case = ET.SubElement(suite, 'testcase', classname='FanTaiYingRealMainUiTest', name=node)
    if not passed:
        ET.SubElement(case, 'failure', message='Real playback acceptance incomplete').text = (
            'See node JSON and environment-checkpoint.json; no real playback pass was established.')
    ET.ElementTree(suite).write(directory / ('TEST-real-' + node + '.xml'), encoding='utf-8', xml_declaration=True)
    return data


def aggregate(directory, nodes):
    results = {}
    for node in nodes:
        folder = directory / ('p0-node-' + node)
        data = read_report(folder / 'environment-checkpoint.json')
        report = read_report(folder / (node + '.json'))
        gate = subprocess.run(['python3', str(Path(__file__).with_name('verify_live_node_acceptance.py')),
                               '--node', node, '--directory', str(folder)],
                              capture_output=True, text=True)
        infrastructure = all(data.get(k) for k in ('kvm_ready', 'emulator_booted', 'apks_installed', 'instrumentation_started'))
        results[node] = {
            'environment': data,
            'real_playback_accepted': bool(infrastructure and gate.returncode == 0 and
                                          report.get('verdict') == 'REAL_PLAYBACK_PASS'),
            'verdict': report.get('verdict', 'TEST_INFRASTRUCTURE_FAILED'),
            'failure_stage': report.get('failure_stage'),
            'failure_code': report.get('failure_code'),
        }
    jobs = {k: os.environ.get(k) for k in ('VALIDATION_RESULT', 'BUILD_RESULT', 'NODES_RESULT', 'TV_RESULT')}
    accepted = all(x['real_playback_accepted'] for x in results.values()) and all(x == 'success' for x in jobs.values())
    summary = {'head': os.environ.get('GITHUB_SHA'), 'run_id': os.environ.get('GITHUB_RUN_ID'),
               'event': os.environ.get('GITHUB_EVENT_NAME'), 'jobs': jobs, 'nodes': results,
               'all_requested_nodes_accepted': accepted,
               'p0_complete': accepted and set(nodes) == {'cheese', 'wanou'}}
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'environment-checkpoint.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as out:
            out.write('## Real playback acceptance\n\n')
            for node, result in results.items():
                out.write(f"- {node}: {result['verdict']}; accepted={result['real_playback_accepted']}\n")
            out.write(f"\nP0 complete: {summary['p0_complete']}\n")
    return accepted


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', default='evidence')
    parser.add_argument('--aggregate')
    parser.add_argument('--node', choices=['cheese', 'wanou', 'both'], required=True)
    args = parser.parse_args()
    if args.aggregate:
        nodes = ['cheese', 'wanou'] if args.node == 'both' else [args.node]
        raise SystemExit(0 if aggregate(Path(args.aggregate), nodes) else 1)
    if args.node == 'both':
        parser.error('per-device checkpoint requires one node')
    checkpoint(Path(args.directory), args.node)
