"""Regression for production source-set escapes in the legacy-core guard."""
from pathlib import Path
import tempfile
import unittest
from check_playback_boundary import violations, without_comments, verify_manager_route


class PlaybackBoundaryTest(unittest.TestCase):
    def test_session_wrapper_preserves_sole_adapter_and_exclusive_engine_ownership(self):
        panel = 'val playback = HerrinPlayerSession(context, media, preferences)'
        session = 'private val legacy = if (original == null) { HerrinPlaybackAdapter(context, media) } else null'
        verify_manager_route(panel, session)
        for wrong_panel, wrong_session in (
            (panel + '; HerrinPlaybackAdapter(context, media)', session),
            (panel + '; HerrinPlayerSession(context, other, preferences)', session),
            (panel, 'private val legacy = HerrinPlaybackAdapter(context, media)'),
            (panel, session + '; HerrinPlaybackAdapter(context, other)'),
        ):
            with self.assertRaises(AssertionError):
                verify_manager_route(wrong_panel, wrong_session)

    def test_comments_do_not_mask_code_after_quoted_url(self):
        code = 'val url = "https://host/"; SpiderProxyPlayback.call() // WebView\n/* WebView */'
        clean = without_comments(code)
        self.assertIn('SpiderProxyPlayback.call()', clean)
        self.assertNotIn('WebView', clean)
        self.assertIn('WebView()', without_comments('val browser = WebView()'))

    def test_debug_java_and_relocated_kotlin_cannot_reintroduce_legacy(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for relative, code in {
                "debug/java/other/Proxy.java": "import tv.herrin.app.SpiderLoopbackProxy;",
                "main/kotlin/other/Bridge.kt": "val result = SpiderProxyPlayback.rebaseUrl(url)",
                "release/java/other/HerrinSpiderHlsProxyRewrite.java": "class Renamed {}",
            }.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(code)
            self.assertEqual(len(violations(root)), 3)

    def test_test_only_legacy_and_provenance_comments_remain_allowed(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for relative, code in {
                "test/java/SpiderProxyPlayback.kt": "object SpiderProxyPlayback {}",
                "androidTest/java/SpiderLoopbackProxy.kt": "class SpiderLoopbackProxy",
                "liveAndroidTest/java/Fixture.kt": "val old = SpiderProxyPlayback",
                "main/java/Host.java": "/* removed SpiderProxyPlayback */ class Host {}",
            }.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(code)
            self.assertEqual(violations(root), [])
