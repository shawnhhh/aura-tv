import unittest
from check_startup_probe import check_ui, check_lifecycle, check_hot_resume

WINDOW = "mCurrentFocus=Window{abc u0 tv.herrin.app/tv.herrin.app.MainActivity}"
TRACE = ("I HerrinStartupPhase: phase=wordmark_visible activity_to_phase_ms=200\n"
         "I HerrinStartup: activity_to_first_draw_ms=100 first_draw_to_wordmark_ms=100 "
         "home_init_ms=250 network_start_ms=300 wordmark_complete_ms=2500 activity_to_home_ms=4500")


def ui(category="首页", details='enabled="true" clickable="true"', extra="", brand="HERRIN"):
    def node(text, attrs="", children=""):
        return (f'<node package="tv.herrin.app" text="{text}" bounds="[10,10][100,50]" '
                f'enabled="true" {attrs}>{children}</node>')
    # Details attributes are separate to allow disabled/hidden negative cases.
    action = f'<node package="tv.herrin.app" text="详情" bounds="[10,10][100,50]" {details}/>'
    return '<hierarchy>' + node(brand) + node(category, 'clickable="true" focused="true"') + action + extra + '</hierarchy>'


class StartupProbeTest(unittest.TestCase):
    def test_confirmed_details_only_home_requires_real_focus_and_completion(self):
        check_ui(ui(), WINDOW)
        self.assertEqual(4500, check_lifecycle(TRACE)["activity_to_home_ms"])

    def test_visible_underlay_without_completion_is_rejected(self):
        with self.assertRaises(AssertionError):
            check_lifecycle(TRACE.replace("activity_to_home_ms=4500", "activity_to_home_ms=-1"))

    def test_disabled_or_unclickable_details_is_rejected(self):
        for attrs in ('enabled="false" clickable="true"', 'enabled="true" clickable="false"'):
            with self.subTest(attrs=attrs), self.assertRaises(AssertionError):
                check_ui(ui(details=attrs), WINDOW)

    def test_old_play_button_cannot_satisfy_the_home_contract(self):
        play = '<node package="tv.herrin.app" text="播放" bounds="[10,10][100,50]" enabled="true" clickable="true"/>'
        with self.assertRaises(AssertionError):
            check_ui(ui(extra=play), WINDOW)

    def test_launcher_or_wrong_navigation_focus_is_rejected(self):
        with self.assertRaises(AssertionError):
            check_ui(ui(), "mCurrentFocus=Window{launcher}")
        with self.assertRaises(AssertionError):
            check_ui(ui(category="电影"), WINDOW)

    def test_missing_brand_is_rejected(self):
        with self.assertRaises(AssertionError):
            check_ui(ui(brand=""), WINDOW)

    def test_nested_label_uses_its_actual_clickable_owner(self):
        xml = ui(details='enabled="true" clickable="false"')
        xml = xml.replace('<node package="tv.herrin.app" text="详情"', '<node package="tv.herrin.app" bounds="[10,10][100,50]" enabled="true" clickable="true"><node package="tv.herrin.app" text="详情"').replace('clickable="false"/>', 'clickable="false"/></node>')
        check_ui(xml, WINDOW)

    def test_deferred_network_or_incomplete_reveal_is_rejected(self):
        for trace in (TRACE.replace("network_start_ms=300", "network_start_ms=4500"),
                      TRACE.replace("wordmark_complete_ms=2500", "wordmark_complete_ms=5000")):
            with self.subTest(trace=trace), self.assertRaises(AssertionError):
                check_lifecycle(trace)

    def test_hot_resume_requires_real_movie_navigation_and_no_replay(self):
        title = '<node package="tv.herrin.app" text="精选电影 · 演示片库" bounds="[10,10][100,50]" enabled="true"/>'
        check_ui(ui(category="电影", extra=title), WINDOW, "电影")
        check_hot_resume(TRACE, TRACE)
        with self.assertRaises(AssertionError):
            check_hot_resume(TRACE, TRACE + "\nI HerrinStartupPhase: phase=wordmark_visible activity_to_phase_ms=200")

    def test_fatal_error_cannot_be_hidden_by_home_markers(self):
        with self.assertRaises(AssertionError):
            check_lifecycle(TRACE + "\nFATAL EXCEPTION: main")


if __name__ == "__main__":
    unittest.main()
