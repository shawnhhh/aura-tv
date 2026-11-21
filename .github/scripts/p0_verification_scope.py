"""Reuse only the exact app sources already validated by run 44714558232."""
import json
import os
import subprocess
from pathlib import Path

VALIDATED_APP_SHA = "3e1da7241b7ec2645cb19450695fe0e23a33bdc2"
VALIDATED_RUN = 44714558232
VALIDATED_STARTUP_SHA = "ec40e7b988b19a7abe3046186663b7c3a1e7ad8f"
VALIDATED_STARTUP_RUN = 44861586714
BRANCH = "codex/herrin-p0-live-playback-20261110"
PROBES = {
    "android-tv/scripts/startup_probe.sh",
    "android-tv/scripts/check_startup_probe.py",
    "android-tv/scripts/test_check_startup_probe.py",
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
    if os.environ.get("GITHUB_EVENT_NAME") == "push" and os.environ.get("GITHUB_REF_NAME") == BRANCH:
        try:
            # Compare cumulatively to the verified SHA, not merely to the last
            # push: an intervening app/dependency/fixture change must run all gates.
            paths = subprocess.check_output(["git", "diff", "--name-only", "-z", VALIDATED_APP_SHA, "HEAD"]).decode().rstrip("\0").split("\0")
            reuse = startup_only(paths) and all(Path(path).is_file() for path in PROBES)
            if reuse:
                startup_paths = subprocess.check_output(["git", "diff", "--name-only", "-z", VALIDATED_STARTUP_SHA, "HEAD"]).decode().rstrip("\0").split("\0")
                reuse_startup = abba_only(paths, startup_paths)
        except (subprocess.CalledProcessError, UnicodeError):
            reuse = False
    result = {"startup_only": reuse,
              "abba_only": reuse_startup,
              "checkout_sha": subprocess.check_output(["git", "rev-parse", "HEAD"]).decode().strip(),
              "reused_app_sha": VALIDATED_APP_SHA if reuse else None,
              "reused_validation_run": VALIDATED_RUN if reuse else None,
              "reused_api31_sha": VALIDATED_STARTUP_SHA if reuse_startup else None,
              "reused_api31_run": VALIDATED_STARTUP_RUN if reuse_startup else None,
              "changed_paths_since_validated_app": paths,
              "changed_paths_since_validated_api31": startup_paths,
              "note": "Reused results are from the stated SHAs, not new executions."}
    target = Path("verification-scope.json")
    target.write_text(json.dumps(result, indent=2))
    print(target.read_text())
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write("startup_only=" + str(reuse).lower() + "\n")
        output.write("abba_only=" + str(reuse_startup).lower() + "\n")


if __name__ == "__main__":
    main()
