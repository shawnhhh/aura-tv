# FongMi complete upstream baseline

`upstream/` is the entire unmodified FongMi/TV checkout, pinned by the Git
submodule and `baseline.lock.json`. It is a baseline candidate, **not yet a
verified playable baseline or an integrated HERRIN module**. It is deliberately
not added to HERRIN's source sets before phase 1 passes.

```sh
git submodule update --init android-tv/vendor/fongmi-core/upstream
python3 android-tv/scripts/audit_fongmi_baseline.py
```

`upstream-files.json` lists every upstream tracked file and Git blob ID, including
build inputs, resources, CatVod, runtime modules and upstream license notices.
The audit checks both Git provenance and actual checkout bytes; it never runs
Spider code or treats file presence as playback evidence.

The original license is `upstream/LICENSE.md` (GPL-3.0). Preserve original
copyright notices. A derivative APK requires corresponding source and build
materials under compatible terms. The license and reproducibility of external
AAR/native inputs still require separate verification; repository-level GPL
does not establish their provenance. Do not substitute stock Media3 for the
custom upstream API, copy unknown binaries, or change application identity to
bypass provider controls.

See `docs/live-spider/20261111-fongmi-core-baseline.zh-CN.md` at the repository
root for the actual execution-path audit and phase gates.
