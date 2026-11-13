"""Collector regressions only; these fixtures are never real-node evidence."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('checkpoint', Path(__file__).with_name('live_ci_checkpoint.py'))
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class CheckpointTest(unittest.TestCase):
    def test_missing_device_report_is_infrastructure_failure_not_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            data = collector.checkpoint(path, 'cheese')
            self.assertFalse(data['emulator_booted'])
            self.assertFalse(data['provider_report_present'])
            suite = ET.parse(path / 'TEST-real-cheese.xml').getroot()
            self.assertEqual('1', suite.attrib['failures'])
            self.assertEqual('0', suite.attrib['skipped'])

    def test_installed_apk_and_junit_ok_do_not_promote_failed_provider(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            for marker in ('kvm-ready', 'emulator-booted', 'apks-installed', 'instrumentation-started'):
                (path / marker).touch()
            (path / 'instrumentation.txt').write_text('OK (1 test)')
            (path / 'wanou.json').write_text(json.dumps({'verdict': 'FAIL_PLAYBACK',
                 'failure_stage': 'search', 'failure_code': 'SOURCE_EMPTY_RESPONSE'}))
            data = collector.checkpoint(path, 'wanou')
            self.assertTrue(data['apks_installed'])
            self.assertEqual('SOURCE_EMPTY_RESPONSE', data['provider_failure_code'])
            self.assertEqual('1', ET.parse(path / 'TEST-real-wanou.xml').getroot().attrib['failures'])

    def test_failed_instrumentation_cannot_be_promoted_by_a_passing_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            path = directory / 'p0-node-cheese'
            path.mkdir()
            for marker in ('kvm-ready', 'emulator-booted', 'apks-installed', 'instrumentation-started'):
                (path / marker).touch()
            (path / 'instrumentation.txt').write_text('INSTRUMENTATION_FAILED')
            (path / 'cheese.json').write_text(json.dumps({'verdict': 'REAL_PLAYBACK_PASS'}))
            data = collector.checkpoint(path, 'cheese')
            self.assertFalse(data['instrumentation_completed'])
            with patch.object(collector.subprocess, 'run', return_value=SimpleNamespace(returncode=0)):
                self.assertFalse(collector.aggregate(directory, ['cheese']))
            summary = json.loads((directory / 'environment-checkpoint.json').read_text())
            self.assertFalse(summary['nodes']['cheese']['real_playback_accepted'])

    def test_each_requested_film_needs_its_own_correctly_identified_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            folder = directory / 'p0-node-cheese-shinchan'
            folder.mkdir()
            (folder / 'environment-checkpoint.json').write_text(json.dumps(dict.fromkeys(
                ('kvm_ready', 'emulator_booted', 'apks_installed', 'instrumentation_started', 'instrumentation_completed'), True)))
            # A passing report for the other title must never satisfy Shinchan.
            (folder / 'cheese.json').write_text(json.dumps({'verdict': 'REAL_PLAYBACK_PASS', 'authorized_title': '小城良方'}))
            with patch.object(collector.subprocess, 'run', return_value=SimpleNamespace(returncode=0)):
                self.assertFalse(collector.aggregate(directory, ['cheese'], ['xiaocheng', 'shinchan']))
            result = json.loads((directory / 'environment-checkpoint.json').read_text())
            self.assertEqual(2, len(result['nodes']))
            self.assertFalse(result['nodes']['cheese/shinchan']['real_playback_accepted'])
            self.assertFalse(result['nodes']['cheese/xiaocheng']['real_playback_accepted'])

    def test_aggregate_missing_artifacts_persists_checkpoint_and_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(collector.aggregate(Path(tmp), ['cheese', 'wanou']))
            data = json.loads((Path(tmp) / 'environment-checkpoint.json').read_text())
            self.assertFalse(data['p0_complete'])
            self.assertEqual('TEST_INFRASTRUCTURE_FAILED', data['nodes']['cheese']['verdict'])


if __name__ == '__main__':
    unittest.main()
