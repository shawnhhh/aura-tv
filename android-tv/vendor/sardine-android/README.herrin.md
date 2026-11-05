# Sardine Android v0.9 source dependency

Official upstream: https://github.com/thegrizzlylabs/sardine-android
Tag: `v0.9`; tag object `82317cf10940395551898f35bc02f2c162d53e80`;
commit `d0af7ae8e7ee0654a763c4c6f638a5e98b1782e9`.
License: Apache-2.0, retained verbatim in LICENSE and packaged in APK assets.

2026-11-05 HERRIN build change: replace the JitPack-generated
`com.github.thegrizzlylabs:sardine-android:0.9` AAR with **all 90 unchanged Java
sources from that same release**. The app compiles these sources directly;
there is no new download repository, substitute binary, stub or plugin execution.
The original build.gradle and empty AndroidManifest.xml are reference material,
not executed/merged. UPSTREAM_SHA1.txt records the official Git blobs of all
94 imported files. scripts/verify_sardine_vendor.py checks these in Debug CI.

Host build differences: reuse the app's Java 17/Android 35 settings and OkHttp
4.12.0; declare upstream's Simple XML 2.7.1 with the same stax/stax-api/xpp3
exclusions. Apply the original consumer ProGuard rules to the app release build.
No resources, components or native libraries occur in the upstream AAR manifest.
The complete public package/API remains available to approved Java Spider code;
this does not authorize executing any new Spider. Existing NAS/cloud functionality
is retained. SourceDependency tests exercise actual PROPFIND XML binding and
authenticated Range media reads/error propagation with local fixtures.

JitPack is excluded from the current cloud network policy and no writable
environment-config tool is exposed. Source compilation is a lawful, explicit
dependency build choice using the allowed official upstream, not a proxy/mirror
route to that blocked host. No upstream runtime build scripts or tests were run;
HERRIN compiles the audited library sources with its own Gradle configuration.
