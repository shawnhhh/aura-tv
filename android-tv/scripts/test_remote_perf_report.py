import unittest

from remote_perf_report import parse_remote_performance, render_markdown


class RemotePerfReportTest(unittest.TestCase):
    def test_extracts_numbers_without_leaking_user_content(self):
        log = (
            "10-30 13:11:00.333 I HerrinPerf: m443_vertical_dpad_events=32 "
            "focus_success=32 key_to_focus_p95_ms=48.12 "
            "end_to_idle_p95_ms=152 url=https://secret.invalid/token\n"
            "10-30 13:11:09.411 I HerrinPerf: m443_horizontal_dpad_events=40 "
            "focus_success=40 key_to_focus_p95_ms=39.20 "
            "end_to_idle_p95_ms=145 frame_p95_ms=18.21 "
            "frames_total=100 frames_over_16_67ms=22 "
            "frames_over_33_33ms=3 long_frame_pct=22.00\n"
            "10-30 13:12:07.200 I HerrinPerf: m443_rapid_burst_events=20 "
            "last_focus_correct=1 burst_to_final_focus_ms=1270\n"
        )
        metrics = parse_remote_performance(log)
        self.assertEqual(32, metrics["vertical"]["focus_success"])
        self.assertEqual(40, metrics["horizontal"]["events"])
        self.assertEqual(22, metrics["horizontal"]["frames_over_16_67ms"])
        self.assertEqual(22.0, metrics["horizontal"]["long_frame_pct"])
        self.assertEqual(20, metrics["burst"]["events"])
        self.assertEqual(1, metrics["burst"]["last_focus_correct"])
        formatted = "\n".join(render_markdown(metrics))
        self.assertIn("48.12ms", formatted)
        self.assertIn("22.0%", formatted)
        self.assertNotIn("secret.invalid", str(metrics) + formatted)
        self.assertNotIn("url", str(metrics))

    def test_empty_log_uses_explicit_unavailable_not_invented_values(self):
        self.assertEqual({}, parse_remote_performance("nothing recorded"))
        lines = render_markdown({})
        self.assertIn("未采集", "\n".join(lines))
        self.assertNotIn("0ms", "\n".join(lines))

    def test_same_emulator_render_baseline_is_reported_only_when_measured(self):
        line = (
            "I HerrinPerf: m445_redraw_frames=24 redraw_p95_ms=189.41 "
            "navigation_frames=51 navigation_p95_ms=206.28 "
            "navigation_to_redraw_ratio=1.09"
        )
        metrics = parse_remote_performance(line)
        self.assertEqual(24, metrics["frame_baseline"]["redraw_frames"])
        self.assertEqual(1.09, metrics["frame_baseline"]["navigation_to_redraw_ratio"])
        report = "\n".join(render_markdown(metrics))
        self.assertIn("189.41ms", report)
        self.assertIn("206.28ms", report)
        self.assertIn("1.09", report)
        self.assertNotIn("82.00", report)

    def test_missing_redraw_samples_are_explicitly_unavailable(self):
        line = (
            "I HerrinPerf: m445_redraw_frames=0 redraw_p95_ms=unavailable "
            "navigation_frames=10 navigation_p95_ms=220.00 "
            "navigation_to_redraw_ratio=unavailable"
        )
        metrics = parse_remote_performance(line)["frame_baseline"]
        self.assertNotIn("redraw_p95_ms", metrics)
        self.assertNotIn("navigation_to_redraw_ratio", metrics)

    def test_dense_film_rail_reports_real_focus_callback_latency(self):
        raw = (
            "I HerrinPerf: m447_dense_dpad_events=16 focus_success=16 "
            "key_to_focus_p95_ms=72.42 end_to_idle_p95_ms=287 "
            "frame_p95_ms=199 frames_total=43"
        )
        result = parse_remote_performance(raw)["dense_catalog"]
        self.assertEqual(16, result["focus_success"])
        self.assertEqual(72.42, result["key_to_focus_p95_ms"])
        rendered = "\n".join(render_markdown({"dense_catalog": result}))
        self.assertIn("12 站点", rendered)
        self.assertIn("72.42ms", rendered)
        self.assertIn("199.0ms", rendered)

    def test_non_herrin_tag_and_unavailable_are_ignored(self):
        log = (
            "I OtherTag: m443_horizontal_dpad_events=40 focus_success=40\n"
            "I HerrinPerf: m443_horizontal_dpad_events=40 focus_success=40 "
            "frame_p95_ms=unavailable long_frame_pct=unavailable\n"
        )
        metrics = parse_remote_performance(log)
        self.assertEqual(40, metrics["horizontal"]["focus_success"])
        self.assertNotIn("frame_p95_ms", metrics["horizontal"])
        self.assertNotIn("long_frame_pct", metrics["horizontal"])


if __name__ == "__main__":
    unittest.main()
