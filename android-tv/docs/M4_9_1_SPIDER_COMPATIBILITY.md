# HERRIN M4.9.1 — Spider catalog fallback and native library loading

This is a targeted follow-up to the M4.9 Draft PR #30. Keep the PR in Draft;
do not merge it into the base branch or master without review.

## Public configuration inspection (2026-10-28)

`http://www.饭太硬.net/tv` served a valid JPEG with appended Base64 TVBox
configuration data. The decoded configuration declared 49 sites and six live
groups. Its Spider URL was HTTPS with an `;md5;` fingerprint; downloading the
referenced file and checking that fingerprint succeeded. The archive contained
`classes.dex`, `assets/ftyguard_v7.so`, and `assets/ftyguard_v8.so`.

These observations verify metadata and integrity only. They **do not** establish
that the external Guard plugin can initialize, return real films/posters or play
video on TCL hardware. No protected plugin was executed during inspection.

## Changes

- The ABI-specific native library is now extracted as `libftyguard_v8.so` or
  `libftyguard_v7.so`, so Android's `DexClassLoader` native-library search can
  resolve the expected `lib<name>.so` basename. Cached extractions are checked
  against the hash of the matching entry from the verified archive.
- Empty or failed `homeContent(true)` no longer blocks other normal CatVod
  catalog methods. The adapter tries `homeVideoContent`, then an unfiltered
  home call, then up to six advertised categories. Film/series/anime categories
  are preferred, and one failed category does not suppress later ones.
- Failures carry bounded method/category diagnostic context. No synthetic film
  entries are generated when every real catalog call fails.
- Added JVM unit tests for category fallback, exception recovery and native
  library naming. The existing IPC/process-isolation tests remain in place.

## Verification boundary

Run `python3 scripts/check_static.py`, Android Debug tests/Lint/APK and Android
TV emulator tests in CI. An actual ARM TCL T7L Ultra must still verify:

1. Apply the same explicit TVBox source, open Advanced diagnostics and confirm
   an actual Spider site returns movie titles and `vod_pic` URLs.
2. Verify the Home page displays real posters and opens film details.
3. Try playback with a stream the user is authorized to access; test
   subtitles/audio if applicable, and confirm Back/D-pad behavior.
4. Reapply a source after a plugin failure, and confirm the HERRIN UI remains
   available. Collect `HerrinCatVod` / `HerrinSpiderWorker` logcat evidence when
   a protected plugin still fails to initialize.

The worker remains in an independent app process under the same UID, **not** a
permission sandbox. Protected plugins can require an Activity or other native
APIs unavailable in a service process; this patch does not claim to bypass
those requirements. Web-page parsers, DRM and unauthorized streams are outside
the scope of this follow-up.
