import subprocess
import tempfile
import unittest
from pathlib import Path
from capture_startup_ui import capture
from check_startup_probe import check_ui


class SnapshotTransportTest(unittest.TestCase):
    def fixture(self, responses):
        calls = []
        responses = iter(responses)
        def run(argv, **kwargs):
            calls.append(argv)
            if argv[1] == "exec-out":
                return subprocess.CompletedProcess(argv, 0, next(responses), b"")
            return subprocess.CompletedProcess(argv, 0, b"", b"")
        return run, calls

    def test_null_root_retries_only_with_fresh_remote_files_and_keeps_failure(self):
        valid = b'<hierarchy><node package="tv.herrin.app"/></hierarchy>'
        run, calls = self.fixture([b"cat: no such file", valid])
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "home.xml"
            capture("/sdcard/probe", target, run)
            self.assertEqual(valid, target.read_bytes())
            self.assertEqual(b"cat: no such file", (Path(folder) / "home-attempt-1.xml").read_bytes())
        self.assertEqual(["/sdcard/probe.1.xml", "/sdcard/probe.2.xml"],
                         [args[-1] for args in calls if args[1] == "exec-out"])

    def test_exhausted_transport_cannot_reuse_an_old_success(self):
        run, calls = self.fixture([b"", b"not xml", b"<hierarchy/>"])
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "home.xml"
            target.write_text('<hierarchy><node/></hierarchy>')
            with self.assertRaises(RuntimeError):
                capture("/sdcard/probe", target, run)
            self.assertFalse(target.exists())
        self.assertEqual(3, sum(args[1] == "exec-out" for args in calls))

    def test_valid_but_wrong_ui_is_not_retried_or_accepted(self):
        run, calls = self.fixture([b'<hierarchy><node package="launcher"/></hierarchy>'])
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "home.xml"
            capture("/sdcard/probe", target, run)
            with self.assertRaises(AssertionError):
                check_ui(target.read_text(), "mCurrentFocus=launcher")
        self.assertEqual(1, sum(args[1] == "exec-out" for args in calls))
