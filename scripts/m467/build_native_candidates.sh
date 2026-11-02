#!/usr/bin/env bash
# Build native *candidates* from pinned FongMi/mpv-android; never auto-bundle into Herrin.
set -euo pipefail
MPV_COMMIT="049ade89abc85fc05ef73dc9ba6f1810466c9f8a"
NDK_VERSION="30.0.16248370"
usage() {
  cat <<'HELP'
Usage: bash scripts/m467/build_native_candidates.sh --mpv-source /path/to/FongMi/mpv-android [--dry-run]
Requires a checked-out exact pinned upstream SHA and its installed NDK r30/build deps.
Builds arm64-v8a and armeabi-v7a .so candidates with upstream's native-only mode.
Does NOT produce compatible FongMi/media AARs or claim runtime success.
HELP
}
source_dir=""
dry_run=0
while (($#)); do
  case "$1" in
    --mpv-source) source_dir="${2:-}"; shift 2;;
    --dry-run) dry_run=1; shift;;
    --help|-h) usage; exit 0;;
    *) echo "Unknown argument: $1" >&2; exit 64;;
  esac
done
[[ -n "$source_dir" ]] || { usage >&2; exit 64; }
source_dir="$(cd "$source_dir" && pwd)"
[[ -f "$source_dir/buildscripts/buildall.sh" ]] || {
  echo "BLOCKED: upstream buildscripts/buildall.sh absent" >&2; exit 2;
}
actual="$(git -C "$source_dir" rev-parse HEAD)"
[[ "$actual" == "$MPV_COMMIT" ]] || {
  echo "BLOCKED: wrong upstream SHA $actual; expected $MPV_COMMIT" >&2; exit 2;
}
depinfo="$source_dir/buildscripts/include/depinfo.sh"
grep -Eq '^v_ndk=r30$' "$depinfo" && grep -Eq '^v_ndk_n=30\.0\.16248370$' "$depinfo" || {
  echo "BLOCKED: pinned NDK contract changed" >&2; exit 2;
}
grep -Eq 'android_api=24' "$source_dir/buildscripts/buildall.sh" || {
  echo "BLOCKED: upstream Android API contract changed" >&2; exit 2;
}
[[ -d "$source_dir/buildscripts/sdk/android-ndk-r30/toolchains/llvm/prebuilt" ]] || {
  echo "BLOCKED: missing NDK $NDK_VERSION under upstream buildscripts/sdk/" >&2
  echo "Prepare prerequisites with upstream buildscripts/download.sh explicitly." >&2; exit 2;
}
command -v python3 >/dev/null && command -v readelf >/dev/null || {
  echo "BLOCKED: python3/readelf required" >&2; exit 2;
}
printf 'FongMi/mpv-android %s, NDK %s, API24\n' "$actual" "$NDK_VERSION"
for entry in "arm64:arm64-v8a" "armv7l:armeabi-v7a"; do
  arch="${entry%%:*}"
  abi="${entry#*:}"
  if ((dry_run)); then
    printf 'DRY RUN: MPV_ANDROID_NATIVE_ONLY=1 bash buildscripts/buildall.sh --arch %q mpv-android\n' "$arch"
    continue
  fi
  (
    cd "$source_dir"
    MPV_ANDROID_NATIVE_ONLY=1 bash buildscripts/buildall.sh --arch "$arch" mpv-android
  )
  output="$source_dir/app/src/main/libs/$abi"
  for library in libmpv.so libplayer.so libffmpeg.so; do
    [[ -s "$output/$library" ]] || {
      echo "BLOCKED: missing $abi/$library from upstream native build" >&2; exit 2;
    }
    dynamic="$(readelf -d "$output/$library")"
    if grep -Eq 'Shared library: \[lib(av|sw)[^]]+\.so' <<< "$dynamic"; then
      echo "BLOCKED: $abi/$library links split FFmpeg" >&2; exit 2;
    fi
  done
  python3 "$source_dir/buildscripts/include/ffmpeg-shared.py" verify-package "$output"
  echo "Native candidate verified: $abi (not runtime validated)"
done
echo "Native candidate stage complete; matching Media3 AAR/API and TCL tests still required."
