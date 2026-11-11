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

Use the pinned upstream wrapper (Gradle9.7.1), JDK21, SDK37 and AGP9.4:

```sh
cd android-tv
bash vendor/fongmi-core/upstream/gradlew -p fongmi-kernel \
  -PfongmiLibraries=/absolute/path/to/locked-aars \
  -PfongmiDependencyLock=/absolute/path/to/dependencies.json \
  :adapter:assembleDebug
```

The supplied lock must extend `dependencies.json` with real AAR artifacts and
corresponding source/version/license/build materials. Run `verify_dependencies.py`
for its schema. No fake archives, stock Media3 replacement, or downloaded APK
classes are used. See the source candidates and unresolved version mismatch in
that file. The original root LICENSE and per-module notices remain applicable.
This independent build avoids changing HERRIN's stable toolchain before the new
classpath is ready. The shared contract also compiles with plain `javac --release 17`.

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
