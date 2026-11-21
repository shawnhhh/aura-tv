import unittest
from p0_verification_scope import PROBES, startup_only, abba_only


class VerificationScopeTest(unittest.TestCase):
    def test_startup_probe_and_ledger_changes_reuse_the_validated_app(self):
        self.assertTrue(startup_only(list(PROBES) + ["android-tv/docs/detail-player/PROGRESS.md",
                                                   ".github/workflows/p0-playback.yml"]))

    def test_every_application_or_build_change_requires_full_verification(self):
        for path in ("android-tv/app/src/main/java/tv/herrin/app/StartupPresentation.kt",
                     "android-tv/app/build.gradle.kts", "android-tv/settings.gradle.kts",
                     "android-tv/fongmi-kernel/dependencies.json", "android-tv/kernel-contract/build.gradle.kts",
                     "android-tv/app/src/androidTest/java/tv/herrin/app/HerrinSplashTest.kt",
                     "android-tv/app/src/test/java/tv/herrin/app/DiscoveryInvalidationTest.kt",
                     ".github/workflows/android-debug.yml", ".github/scripts/start_smb_fixture.sh",
                     "android-tv/scripts/emulator_ci.sh"):
            with self.subTest(path=path):
                self.assertFalse(startup_only(list(PROBES) + [path]))

    def test_no_probe_change_cannot_claim_startup_only(self):
        for paths in ([], ["android-tv/docs/detail-player/PROGRESS.md"], [".github/workflows/p0-playback.yml"]):
            with self.subTest(paths=paths):
                self.assertFalse(startup_only(paths))

    def test_unknown_paths_fail_closed(self):
        self.assertFalse(startup_only(list(PROBES) + ["android-tv/scripts/unreviewed.py"]))

    def test_abba_control_changes_reuse_only_the_verified_startup(self):
        paths = [".github/workflows/p0-playback.yml", ".github/workflows/tmdb-browse-performance.yml"]
        self.assertTrue(abba_only(list(PROBES) + paths, paths))

    def test_startup_or_app_change_cannot_skip_api31(self):
        for path in list(PROBES) + [".github/workflows/android-startup.yml",
                                   "android-tv/app/src/main/java/tv/herrin/app/StartupPresentation.kt"]:
            with self.subTest(path=path):
                self.assertFalse(abba_only(list(PROBES) + [path], [path]))


if __name__ == "__main__":
    unittest.main()
