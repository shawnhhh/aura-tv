# Whole upstream FongMi integration build

This build compiles the existing pinned checkout directly; it does not download
TV again. `core` contains the entire main/leanback implementation, including
SiteApi, VodConfig, loaders, Proxy, Source, ParseJob, PlayerManager and both
engines. `catvod` is the original project. `contract` is the Android-free host
boundary; `adapter` delegates to upstream APIs without media parsing.

Playback uses original VideoViewModel/VodPlaybackController/VodPlaybackState,
then original PlayerManager.parse. There is no direct SiteApi.playerContent
shortcut. Original resolved netdisk episodes flow through the controller, and
updated opaque selection handles are published through observeDetail. Original
preload/history/progress callbacks remain in the same session. The data-source
wrapper only restricts execution to the explicitly selected/authorized site;
HERRIN's outer router continues to own source order. Playback admission futures
are not video-frame or authorization-success evidence.

The only source host patch generates Nano with a **loopback listener**, preserving
its original process dispatch and response handling. No original UI launcher is
registered. The kernel Application extends original App/Startup and refuses
package/signature impersonation. Original SpiderDialog and runtime modules retain
their legal authorization flows. No QR-success inference is implemented.

## Build after dependency resolution

The original TV@305dff2 expects a **custom Media3 API**, not stock Media3.
The public `FongMi/media` source candidate does not include multiple required
symbols and is **not** a proven compatible replacement.
A second published fork `webhtv/webhtv@4e30ffaf` was SHA-256-checked against
three authentic custom Media3 AARs in Actions run 43144759251. It lacks
`DecoderMode`, `DiscMenuController`, `SubtitleSelectionState`,
`IsoDiscPreparation` and `PlayerView.setBackendPlayer`, so it is **explicitly
rejected** for the FongMi TV 5.8.7 classpath. Do not re-audit or integrate it.
A future proven-compatible Media3 set may be declared with `origin: "media3"`,
full source/commit/license/checksum and must satisfy all original imported APIs. All four required
AARs (MPV, FFmpeg, original hook, tvbus) must be recorded in
`dependencies.json` with real source commits, binary SHA-256s, license texts,
and build instructions. The original two upstream archives are read from the
pinned TV checkout; external ones come from a separate directory. Do not
extract binaries from an APK, create empty placeholders, or mark a mismatched
binary as compatible.

The independent FongMi **Android library** cannot directly depend on
`fileTree('*.aar')`, because AGP cannot package a library AAR's direct local
AAR dependencies correctly. Instead, the staging tool verifies the original
bytes and publishes them to a **generated, immutable-coordinate local Maven
repository**, and the core depends on the same content-hash coordinates.
This does not modify the AARs or replace any Media3 classes.

From `android-tv`, after obtaining actual licensed inputs:

```sh
# Non-failing, actionable JSON before the native dependencies exist:
python3 fongmi-kernel/verify_dependencies.py \
  --directory /absolute/path/to/locked-aars --report

# Fails closed until every mandatory original input is valid.
python3 fongmi-kernel/stage_dependencies.py \
  --directory /absolute/path/to/locked-aars \
  --lock fongmi-kernel/dependencies.json

# Only after the verified Maven artifacts have been generated:
bash vendor/fongmi-core/upstream/gradlew -p fongmi-kernel \
  -PfongmiLibraries=/absolute/path/to/locked-aars \
  -PfongmiDependencyLock=/absolute/path/to/dependencies.json \
  :adapter:assembleDebug
```

The generated Maven repository is in
`android-tv/fongmi-kernel/build/locked-original-maven`. It must **never** be
committed as a substitute for missing original artifacts. The required
toolchain is upstream Gradle 9.7.1, JDK 21, SDK 37 and AGP 9.4.
The real original sources, not a generated player implementation, produce
`:core` and `:adapter`. This independent build avoids changing HERRIN's
stable toolchain before the original classpath is available.
The shared contract also compiles with plain `javac --release 17`.

Use the separate `HERRIN FongMi original dependency preflight` GitHub workflow
to confirm the input-checking scripts are syntactically valid and to record a
non-success dependency report. **The preflight does not compile the original
engine, launch Android, fetch real sources or prove playback.**

## Host integration and cutover

`HerrinKernelGateway` is added to the existing app as a contract consumer. It is
**not yet wired into the current UI or Application**. Complete the dependency
build, remove old same-package FongMi/CatVod adaptations from the app's classpath,
replace stock Media3 with the proven original set, then integrate the adapter
artifact and extend KernelApplication (retaining CrashJournal). Bind the existing
playback surface through FongMiKernel.bind, without creating an ExoPlayer in UI.

On the main thread, install through KernelCutover with a completion that confirms
old Spider workers, parsers, probes, proxies and player sessions are all released.
Only then does the original-kernel factory run. Failure is terminal, with no
legacy factory or retry route. Handles retire on config reload/close, authorized
site order is supplied by the host, and media addresses stay inside the kernel.
When the compile-ready host path exists, route the current catalog/selection UI
through this gateway and delete unreachable legacy implementation plus its tests.
Do not claim those final wiring/deletion steps have already happened.

No real nodes, original APK reference, emulator, full TV suite or ABBA test is
run as part of development. Unified Android/JVM/TV and actual node evidence must
follow integration. A source-set declaration, a successful contract compile,
READY or HTTP response never establishes real video frames or P0 completion.

## 2026-11-22 original hook/tvbus provenance

The two unmodified AARs and their source modules already exist at TV@305dff2.
They are now explicitly locked with binary SHA-256, the original GPL license
path/hash, source revision and original `build-source-modules.sh hook|tvbus`
commands. `missing_upstream` means missing verified lock entries, not necessarily
missing files. An external artifact cannot claim this upstream license.
The complete gate remains DEPENDENCY_BLOCKED for the absent matched MPV/FFmpeg
and custom Media3 inputs. No original-core build or provider playback success is
claimed by verifying these two archives.

## H-005 development — 2026-11-23

All changes in this batch: **未测试，待后续统一验证**. No tests, builds,
dependency compilation, APKs or manually triggered CI were run.

- Code committed: the normal app now hosts a relocated GPL original
  `SpiderDialog` and `QRCode` from TV@305dff2 in its real `:spider`
  FragmentActivity. Only host imports/services, secure-window behavior and the
  opaque QR accessibility label differ. Provenance and original file hashes:
  `app/src/main/java/tv/herrin/app/spiderdialog/upstream.json`.
  `Runtime.dialog` → operation-owned Activity → original dialog → provider
  callback → original pending Spider operation. The host neither polls a cloud
  account itself nor interprets dialog completion as authentication success.
- Code committed: operation/session cancellation, action callback retirement,
  bounded dialog lifetime and rejection of media returned after cancellation.
  Whole-kernel selection switches retire old authorization capabilities before
  new selection; no Cookie/Token cache or expiry policy is invented by the host.
- Dependency blocked: exact `lib-mpvplayer-release.aar`,
  `lib-decoder-ffmpeg-release.aar` and matching custom Media3. Required APIs
  include `MpvConfigFileInspector`, `MpvDecoderMode`, `MpvSubtitleContentLoader`,
  `MpvDrmSupport`, `DecoderMode`, `DiscMenuController`, `SubtitleSelectionState`,
  `IsoDiscPreparation`, and `PlayerView.setBackendPlayer`. The dependency report
  now emits the complete missing imported-class list and a narrow public-method
  gate; it does not establish full binary compatibility.
- Source inspection: official media `release` at
  `2bc207851df311340767e913931ca7b28cab1794` also lacks these custom APIs.
  The incompatible `release-1.11.0-fongmi` head is unchanged. Neither is staged.
- Not developed: preserving an in-flight original dialog across Activity
  destruction. Destruction cancels the pending operation; an explicit retry
  uses the provider's own valid login state. No consumed action is replayed.
- Dependency blocked: final whole-kernel classpath migration/player takeover.
  This batch does not establish real provider authorization or playback frames.
