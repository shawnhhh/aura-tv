import unittest
from await_startup_home import await_home
from test_check_startup_probe import TRACE


class SnapshotLifecycleTest(unittest.TestCase):
    def test_early_valid_ui_cannot_substitute_for_completion(self):
        readings = iter(["I HerrinStartupPhase: phase=wordmark_visible", TRACE])
        waits = []
        self.assertEqual(TRACE, await_home(lambda: next(readings), clock=lambda: 0, pause=waits.append))
        self.assertEqual([0.25], waits)

    def test_missing_completion_has_a_fixed_deadline(self):
        ticks = iter([0, 0, 15])
        with self.assertRaises(TimeoutError):
            await_home(lambda: "no completion", clock=lambda: next(ticks), pause=lambda _: None)

    def test_crash_or_invalid_completion_is_never_retried(self):
        for value in ("FATAL EXCEPTION: main", TRACE.replace("network_start_ms=300", "network_start_ms=4500")):
            with self.subTest(value=value), self.assertRaises(AssertionError):
                await_home(lambda: value, clock=lambda: 0, pause=lambda _: self.fail("Invalid completion retried"))
