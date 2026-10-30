"""Fail TV CI when critical real-device tests silently disappear.

JUnit XMLs can be missing if Android instrumentation discovery or a Gradle
filter changed, even though an APK assembled successfully. This explicit gate
makes a full-suite pass mean that launch, remote focus, catalog, playback,
subtitles, settings, live TV, and negative-path regressions actually ran.
"""
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

REQUIRED_CLASSES = (
    "HerrinSplashTest",
    "HerrinUiSmokeTest",
    "M438SingleWarehousePerformanceTest",
    "M442RemoteBurstPerformanceTest",
    "M443RemoteBurstWithoutIdleTest",
    "M445FrameBaselineTest",
    "M449SettingsNoIdleBurstTest",
    "M453BufferingRemoteFocusInstrumentedTest",
    "M454CmsOneTapRecoveryInstrumentedTest",
    "M456NativeFullscreenPlayerTest",
    "AuthorizedCatalogIntegrationTest",
    "TvBoxVodIntegrationTest",
    "TvBoxWarehouseIntegrationTest",
    "TvBoxLiveIntegrationTest",
    "HttpsPlaybackTest",
    "PlaybackTransportTest",
    "PlaybackButtonsTest",
    "PlaybackSessionIsolationTest",
    "MediaDecoderTest",
    "PosterArtworkTest",
)
MIN_DEVICE_TESTS = 100


def summarize(folder: Path):
    tests = []
    for result_file in folder.rglob("TEST-*.xml"):
        root = ET.parse(result_file).getroot()
        for case in root.iter("testcase"):
            tests.append((
                (case.attrib.get("classname") or "").rsplit(".", 1)[-1],
                case.attrib.get("name", ""),
                "failed" if case.find("failure") is not None or
                            case.find("error") is not None else
                "skipped" if case.find("skipped") is not None else "passed"
            ))
    classes = {cls for cls, _, _ in tests}
    missing = sorted(set(REQUIRED_CLASSES) - classes)
    bad = [(cls, name, status) for cls, name, status in tests if status != "passed"]
    reasons = []
    if len(tests) < MIN_DEVICE_TESTS:
        reasons.append(f"Found only {len(tests)} tests; expected at least {MIN_DEVICE_TESTS}")
    if missing:
        reasons.append("Missing essential Android TV test classes: " + ", ".join(missing))
    if bad:
        reasons.append(f"{len(bad)} failed/skipped test(s): " +
                       ", ".join(f"{cls}.{name}={status}" for cls, name, status in bad[:12]))
    return tests, reasons


if __name__ == "__main__":
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(
        "app/build/outputs/androidTest-results")
    collected, failures = summarize(source)
    print(f"TV full suite discovery: {len(collected)} cases, "
          f"{len(REQUIRED_CLASSES)} mandatory classes")
    if failures:
        print("FULL SUITE NOT ACCEPTED: " + " | ".join(failures), file=sys.stderr)
        raise SystemExit(1)
    print("TV FULL SUITE ACCEPTED: all required classes present, no failures/skips")
