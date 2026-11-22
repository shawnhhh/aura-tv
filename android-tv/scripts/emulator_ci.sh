#!/usr/bin/env bash
set -euo pipefail
cd android-tv
mkdir -p build/emulator-evidence
# Capture diagnostics even when a test fails; the emulator runner terminates ADB after this script.
collect() {
    code=$?
    trap - EXIT
    adb logcat -d > build/emulator-evidence/logcat.txt 2>&1 || true
    adb exec-out base64 -d /data/local/tmp/herrin-test-evidence/player-baseline.b64 > build/emulator-evidence/player-baseline.json || true
    cat build/emulator-evidence/player-baseline.json
    # Only sanitized measurements: the perf test never logs source addresses, film URLs or tokens.
    grep 'HerrinPerf' build/emulator-evidence/logcat.txt > build/emulator-evidence/performance-measurements.log || true
    adb shell dumpsys activity activities > build/emulator-evidence/activities-final.txt 2>&1 || true
    adb pull /data/local/tmp/herrin-test-evidence build/emulator-evidence/test-evidence || true
    adb exec-out screencap -p > build/emulator-evidence/final.png || true
    adb shell uiautomator dump /sdcard/herrin-final.xml || true
    adb pull /sdcard/herrin-final.xml build/emulator-evidence/final.xml >/dev/null 2>&1 || true
    adb logcat -d -s HerrinRemote:D > build/emulator-evidence/remote-trace.log 2>&1 || true
    cat build/emulator-evidence/remote-trace.log
    adb logcat -d -s HerrinRecentFailure:E > build/emulator-evidence/recent-failure.log 2>&1 || true
    cat build/emulator-evidence/recent-failure.log
    # Public fixture semantics only. Preserve the original failing assertion and
    # expose its exact tree even when artifact downloads are unavailable.
    adb logcat -d -s HerrinCachedHeroFailure:D > build/emulator-evidence/cached-hero-failure.log 2>&1 || true
    head -n 256 build/emulator-evidence/cached-hero-failure.log
    # Full PNGs remain in artifacts; keep logs bounded so connector log retrieval is reliable.
    python3 scripts/emulator_report.py "$code" || true
    exit "$code"
}
trap collect EXIT
# Parse/report reliability must be tested before claiming an emulator pass.
python3 -m unittest discover -s scripts -p 'test_remote_perf_report.py' -v
python3 -m unittest discover -s scripts -p 'test_verify_full_tv_suite.py' -v
gradle :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace 2>&1 | tee build/emulator-evidence/build.log
adb logcat -G 16M || true
adb shell setprop log.tag.HerrinRemote DEBUG
bash scripts/emulator_smoke.sh 2>&1 | tee build/emulator-evidence/adb-smoke.log
# Regression preflight for the failures discovered in run 42358864061. This
# supplements the unfiltered suite below; it never makes failed cases optional.
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Explicitly scoped failure diagnosis. The workflow and report label this as
# partial verification; the existing 291/292 full-suite failure remains recorded.
if [[ "${HERRIN_TV_MODE:-full}" == recent-diagnostic ]]; then
    for sample in 1 2 3; do
        adb shell am instrument -w -r -e class 'tv.herrin.app.P0RecentPlaybackReturnTest' tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee "build/emulator-evidence/recent-diagnostic-$sample.txt"
        python3 - "$sample" <<'PYRECENT'
from pathlib import Path
import sys
text=Path(f'build/emulator-evidence/recent-diagnostic-{sys.argv[1]}.txt').read_text()
assert 'OK (4 tests)' in text and 'INSTRUMENTATION_CODE: -1' in text,text
assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text,text
PYRECENT
    done
    python3 - <<'PYRECENTREPORT'
import json,subprocess
from pathlib import Path
result={'source_sha':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
        'mode':'recent-diagnostic','samples':3,'tests_per_sample':4,'full_suite_run':False,
        'physical_TCL':False,'note':'Diagnostic only. Does not replace the recorded full-suite failure.'}
Path('build/emulator-evidence/targeted-summary.json').write_text(json.dumps(result,indent=2))
print('HERRIN_RECENT_DIAGNOSTIC='+json.dumps(result))
PYRECENTREPORT
    exit 0
fi
# Replay the newly discovered index/intent boundary and the original recent
# playback assertions before the unchanged unfiltered suite.
adb shell am instrument -w -r -e class 'tv.herrin.app.PersonalIndexTransactionTest,tv.herrin.app.P0RecentPlaybackReturnTest' tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee build/emulator-evidence/index-recent-preflight.txt
python3 - <<'PYINDEX'
from pathlib import Path
text=Path('build/emulator-evidence/index-recent-preflight.txt').read_text()
assert 'OK (5 tests)' in text and 'INSTRUMENTATION_CODE: -1' in text,text
assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text,text
PYINDEX
# Explicit repair preflight: observe this real production decoder case before
# the complete suite. No original test or full-suite gate is filtered out.
if git log -1 --format=%s | grep -Fq '[baseline-first]'; then
    adb shell am instrument -w -r -e class 'tv.herrin.app.PlaybackDiagnosticsIntegrationTest#productionPlayerMp4AndHlsDecodeForSixtySecondsPauseSeekAndRelease' tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee build/emulator-evidence/player-baseline-preflight.txt
    python3 - <<'PY'
from pathlib import Path
text = Path('build/emulator-evidence/player-baseline-preflight.txt').read_text()
assert 'OK (1 test)' in text and 'INSTRUMENTATION_CODE: -1' in text, text
assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text, text
PY
fi
# Retest the sole full-suite failure from run44924819337 before the retained
# preflights and unfiltered suite. Valid short links are now an explicit requirement.
adb shell am instrument -w -r -e class 'tv.herrin.app.TvBoxWarehouseIntegrationTest#followsValidShortLinksButRefusesUnsafeRedirectsImagesHtmlAndOversizedPayloads' tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee build/emulator-evidence/warehouse-redirect-preflight.txt
python3 - <<'PYWAREHOUSE'
from pathlib import Path
text=Path('build/emulator-evidence/warehouse-redirect-preflight.txt').read_text()
assert 'OK (1 test)' in text and 'INSTRUMENTATION_CODE: -1' in text,text
assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text,text
PYWAREHOUSE
# Retest the remaining failure from run 44705015249 before broader gates.
adb shell am instrument -w -r -e class 'tv.herrin.app.HerrinUiSmokeTest#tmdbDetailPrefetchesBothCmsLayoutsAndSelectsOriginalVodWithoutMediaRequests' tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee build/emulator-evidence/h-detail-rebind-preflight.txt
python3 - <<'PYDETAIL'
from pathlib import Path
text=Path('build/emulator-evidence/h-detail-rebind-preflight.txt').read_text()
assert 'OK (1 test)' in text and 'INSTRUMENTATION_CODE: -1' in text,text
assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text,text
PYDETAIL
# Verify the failed V13 frame-reference case before the retained 20-test and
# unfiltered suites; every original pixel/brightness assertion remains active.
adb shell am instrument -w -r -e class 'tv.herrin.app.HerrinSplashTest#centerWaveStartsInvisibleAndAllSixLettersShareARealWhiteHaloBreath' tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee build/emulator-evidence/v13-frame-preflight.txt
python3 - <<'PYV13'
from pathlib import Path
text=Path('build/emulator-evidence/v13-frame-preflight.txt').read_text()
assert 'OK (1 test)' in text and 'INSTRUMENTATION_CODE: -1' in text,text
assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text,text
PYV13
adb shell am instrument -w -r -e class 'tv.herrin.app.HerrinSplashTest,tv.herrin.app.P0CachedHomeHeroTest,tv.herrin.app.P0CmsCandidateParserTest,tv.herrin.app.P0SpiderDeadlineRecoveryTest,tv.herrin.app.P0SourceSearchLifecycleTest,tv.herrin.app.HerrinUiSmokeTest#onlineSearchProtocolEmptyAndRemoteResult,tv.herrin.app.HerrinUiSmokeTest#advancedMetadataFiltersOpenInsideSearchAndWorkWithDpad,tv.herrin.app.HerrinUiSmokeTest#searchCategoryAndSortAreOperableByRemoteWithProtocolFixture' tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee build/emulator-evidence/p0-regression-preflight.txt
python3 - <<'PY'
from pathlib import Path
text=Path('build/emulator-evidence/p0-regression-preflight.txt').read_text()
assert 'OK (20 tests)' in text and 'INSTRUMENTATION_CODE: -1' in text,text
assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text,text
PY
# Retest all 20 failures from run 44691313055 before the unchanged full suite.
adb shell am instrument -w -r -e class 'tv.herrin.app.EmptySpiderHomeSelectionTest#advancedPreviewRetainsEmptyInitializedSource,tv.herrin.app.EmptySpiderHomeSelectionTest#selectedEmptyHomeCanSearchAndOpenItsRealDetailsWithoutTmdbToken,tv.herrin.app.EmptySpiderHomeSelectionTest#sitePickerRetainsEmptyInitializedSourceAndRestoresItOnRecreate,tv.herrin.app.HerrinUiSmokeTest#myPersonalSourcesShortcutOpensStorageManager,tv.herrin.app.HerrinUiSmokeTest#sourceSiteExplanationIsConsumerFriendlyWithoutRuntimeWords,tv.herrin.app.HerrinUiSmokeTest#tmdbDetailPrefetchesBothCmsLayoutsAndSelectsOriginalVodWithoutMediaRequests,tv.herrin.app.HerrinUiSmokeTest#applyingLiveSourcePopulatesLiveTabWithoutHttpToggle,tv.herrin.app.HerrinUiSmokeTest#applyingTvBoxSourceReplacesDemoHomeAndPopulatesMovieTab,tv.herrin.app.HerrinUiSmokeTest#homeDpadDownMovesFromNavigationToHeroThenFirstPoster,tv.herrin.app.HerrinUiSmokeTest#spiderOnlySourceDoesNotSilentlyFallBackToDemoFilms,tv.herrin.app.HerrinUiSmokeTest#homeHeaderKeepsCompactSettingsAndExactlyTwoSourceInputs,tv.herrin.app.M438SingleWarehousePerformanceTest#singleWarehouseLoadsAllSitesAndDpadHasNoSevereStall,tv.herrin.app.M449SettingsNoIdleBurstTest#rapidSidebarKeysChangeDirectionWithoutOpeningIme,tv.herrin.app.M454CmsOneTapRecoveryInstrumentedTest#wrongSelectedMovieIsRejectedAndRetryUsesOnlyRealSelectedFilm,tv.herrin.app.M454CmsOneTapRecoveryInstrumentedTest#cmsListingWithHttpFirstAndHttpsSecondPlaysWithoutAnotherDetailRequest,tv.herrin.app.P0ExitAndEntryTest#phoneHttpsSaveOfHttpWarehouseRefreshesTvWithoutRemoteApply,tv.herrin.app.P0RecentPlaybackReturnTest#heroDetailsRemainsDetailsAndDetailPlayDecodesThenRestoresFocus,tv.herrin.app.P0RecentPlaybackReturnTest#failedInitialImportAndQuickLookupFallsBackWithoutAnImplicitRescan,tv.herrin.app.StartupParallelPreparationTest#networkStartsBehindCurtainAndSurvivesCurtainRemoval,tv.herrin.app.TmdbBrowsePageTest#nativeDpadTraversesIndependentRowsAndReturnsToTheSameColumn' tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee build/emulator-evidence/h-resume-preflight.txt
python3 - <<'PYRESUME'
from pathlib import Path
text=Path('build/emulator-evidence/h-resume-preflight.txt').read_text()
assert 'OK (20 tests)' in text and 'INSTRUMENTATION_CODE: -1' in text,text
assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text,text
PYRESUME
# Shell-owned evidence survives the package uninstall performed by AGP.
adb shell rm -rf /data/local/tmp/herrin-test-evidence
gradle :app:connectedDebugAndroidTest --stacktrace 2>&1 | tee build/emulator-evidence/instrumentation.log
# Do not report success if instrumentation skipped essential remote/playback cases.
python3 scripts/verify_full_tv_suite.py 2>&1 | tee build/emulator-evidence/full-suite-gate.log
