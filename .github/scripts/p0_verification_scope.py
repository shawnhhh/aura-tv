"""Reuse only the exact app sources already validated by run 44714558232."""
import json
import os
import subprocess
from pathlib import Path

VALIDATED_APP_SHA = "3e1da7241b7ec2645cb19450695fe0e23a33bdc2"
VALIDATED_RUN = 44714558232
VALIDATED_STARTUP_SHA = "ec40e7b988b19a7abe3046186663b7c3a1e7ad8f"
VALIDATED_STARTUP_RUN = 44861586714
VALIDATED_DEBUG_SHA = "82b4fc7b5e519625710f7e5ca5b165b1d72f3f3e"
VALIDATED_DEBUG_RUN = 44921354185
VALIDATED_ABBA_SHA = "4c7a907e870903547aa52cff114f668a4a8a99b9"
VALIDATED_ABBA_RUN = 44924819337
BRANCH = "codex/herrin-p0-live-playback-20261110"
PROBES = {
    "android-tv/scripts/startup_probe.sh",
    "android-tv/scripts/check_startup_probe.py",
    "android-tv/scripts/test_check_startup_probe.py",
    "android-tv/scripts/capture_startup_ui.py",
    "android-tv/scripts/test_capture_startup_ui.py",
    "android-tv/scripts/await_startup_home.py",
    "android-tv/scripts/test_await_startup_home.py",
}
ALLOWED = PROBES | {
    ".github/scripts/p0_verification_scope.py",
    ".github/scripts/test_p0_verification_scope.py",
    ".github/workflows/p0-playback.yml",
    ".github/workflows/android-startup.yml",
    ".github/workflows/tmdb-browse-performance.yml",
    ".github/scripts/tmdb_performance_ab.sh",
    ".github/scripts/tmdb_performance_ab.py",
}
ABBA_ONLY = ALLOWED - PROBES - {".github/workflows/android-startup.yml"}
TV_CONTROL = {".github/scripts/p0_verification_scope.py", ".github/scripts/test_p0_verification_scope.py",
              ".github/workflows/p0-playback.yml", "android-tv/scripts/emulator_ci.sh", ".github/workflows/android-emulator.yml"}


# This exact method is not invoked by the ABBA home/remote test selection.
# Any other AndroidTest, app, dependency or performance-runner change fails closed.
ABBA_REUSE_ALLOWED = PROBES | TV_CONTROL | {
    "android-tv/app/src/androidTest/java/tv/herrin/app/TvBoxWarehouseIntegrationTest.kt",
}


def abba_reusable(paths):
    return bool(paths) and all(p in ABBA_REUSE_ALLOWED or p.startswith("android-tv/docs/") for p in paths)


DIAGNOSTIC_BASE_SHA = "7eff2495c75aa76daf4b59a3a520c146090f95d6"
RECENT_TEST = "android-tv/app/src/androidTest/java/tv/herrin/app/P0RecentPlaybackReturnTest.kt"
DIAGNOSTIC_ALLOWED = TV_CONTROL | PROBES | {RECENT_TEST, ".github/workflows/android-emulator.yml"}


def tv_mode(subject, paths):
    # This is explicitly a failing-case diagnostic, never full-suite acceptance.
    # Any app/build/other-test change restores normal verification; API31 still runs.
    if "[recent-playback-diagnostic]" in subject and RECENT_TEST in paths and all(
            p in DIAGNOSTIC_ALLOWED or p.startswith("android-tv/docs/") for p in paths):
        return "recent-diagnostic"
    return "full"


def tv_only(paths):
    """Only instrumentation changed; app/JVM/fixtures/build inputs must match the verified debug."""
    return any(p.startswith("android-tv/app/src/androidTest/") for p in paths) and all(
        p.startswith("android-tv/app/src/androidTest/") or p.startswith("android-tv/docs/") or p in TV_CONTROL or p in PROBES
        for p in paths)


def startup_only(paths):
    return bool(set(paths) & PROBES) and all(path in ALLOWED or path.startswith("android-tv/docs/") for path in paths)


def abba_only(app_paths, startup_paths):
    return startup_only(app_paths) and bool(startup_paths) and all(
        path in ABBA_ONLY or path.startswith("android-tv/docs/") for path in startup_paths)


def main():
    paths = []
    reuse = False
    reuse_startup = False
    startup_paths = []
    tv_paths = []
    reuse_debug = False
    reuse_abba = False
    abba_paths = []
    mode = "full"
    diagnostic_paths = []
    if os.environ.get("GITHUB_EVENT_NAME") == "push" and os.environ.get("GITHUB_REF_NAME") == BRANCH:
        try:
            # Compare cumulatively to the verified SHA, not merely to the last
            # push: an intervening app/dependency/fixture change must run all gates.
            paths = subprocess.check_output(["git", "diff", "--name-only", "-z", VALIDATED_APP_SHA, "HEAD"]).decode().rstrip("\0").split("\0")
            reuse = startup_only(paths) and all(Path(path).is_file() for path in PROBES)
            if reuse:
                startup_paths = subprocess.check_output(["git", "diff", "--name-only", "-z", VALIDATED_STARTUP_SHA, "HEAD"]).decode().rstrip("\0").split("\0")
                reuse_startup = abba_only(paths, startup_paths)
            if not reuse:
                tv_paths = subprocess.check_output(["git", "diff", "--name-only", "-z", VALIDATED_DEBUG_SHA, "HEAD"]).decode().rstrip("\0").split("\0")
                reuse_debug = tv_only(tv_paths)
            abba_paths = subprocess.check_output(["git", "diff", "--name-only", "-z", VALIDATED_ABBA_SHA, "HEAD"]).decode().rstrip("\0").split("\0")
            reuse_abba = abba_reusable(abba_paths)
            if reuse_debug:
                diagnostic_paths = subprocess.check_output(["git", "diff", "--name-only", "-z", DIAGNOSTIC_BASE_SHA, "HEAD"]).decode().rstrip("\0").split("\0")
                subject = subprocess.check_output(["git", "log", "-1", "--format=%s"]).decode()
                mode = tv_mode(subject, diagnostic_paths)
        except (subprocess.CalledProcessError, UnicodeError):
            reuse = False
            reuse_abba = False
    result = {"startup_only": reuse,
              "tv_only": reuse_debug,
              "tv_mode": mode,
              "diagnostic_base_sha": DIAGNOSTIC_BASE_SHA if mode != "full" else None,
              "changed_paths_since_diagnostic_base": diagnostic_paths,
              "reuse_abba": reuse_abba,
              "reused_abba_sha": VALIDATED_ABBA_SHA if reuse_abba else None,
              "reused_abba_run": VALIDATED_ABBA_RUN if reuse_abba else None,
              "changed_paths_since_verified_abba": abba_paths,
              "abba_only": reuse_startup,
              "checkout_sha": subprocess.check_output(["git", "rev-parse", "HEAD"]).decode().strip(),
              "reused_app_sha": VALIDATED_APP_SHA if reuse else None,
              "reused_validation_run": VALIDATED_RUN if reuse else None,
              "reused_api31_sha": VALIDATED_STARTUP_SHA if reuse_startup else None,
              "reused_api31_run": VALIDATED_STARTUP_RUN if reuse_startup else None,
              "reused_debug_sha": VALIDATED_DEBUG_SHA if reuse_debug else None,
              "reused_debug_run": VALIDATED_DEBUG_RUN if reuse_debug else None,
              "changed_paths_since_verified_debug": tv_paths,
              "changed_paths_since_validated_app": paths,
              "changed_paths_since_validated_api31": startup_paths,
              "note": "Reused results are from the stated SHAs, not new executions."}
    target = Path("verification-scope.json")
    target.write_text(json.dumps(result, indent=2))
    print(target.read_text())
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write("startup_only=" + str(reuse).lower() + "\n")
        output.write("abba_only=" + str(reuse_startup).lower() + "\n")
        output.write("tv_only=" + str(reuse_debug).lower() + "\n")
        output.write("tv_mode=" + mode + "\n")
        output.write("reuse_abba=" + str(reuse_abba).lower() + "\n")


if __name__ == "__main__":
    main()
