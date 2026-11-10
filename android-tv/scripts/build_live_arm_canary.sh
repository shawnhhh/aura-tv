#!/usr/bin/env bash
set -euo pipefail
sdk_root="${ANDROID_HOME:?Android SDK required}"
ndk_root=$(find "$sdk_root/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
test -d "$ndk_root/toolchains/llvm/prebuilt/linux-x86_64"
compiler_dir="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
for abi in arm64-v8a armeabi-v7a; do
    if [[ "$abi" == arm64-v8a ]]; then target=aarch64-linux-android26; else target=armv7a-linux-androideabi26; fi
    output="android-tv/app/build/live-probe-assets/native-probe/$abi"
    mkdir -p "$output"
    "$compiler_dir/$target-clang" -shared -fPIC -O2 android-tv/test-fixtures/arm-canary/probe.c -o "$output/libherrinarmprobe.so"
    "$compiler_dir/llvm-readelf" -h "$output/libherrinarmprobe.so" > "$output/elf.txt"
done
# Only ARM64 in this package: installing it is a real package-manager capability check.
mkdir -p android-tv/app/build/live-probe-jni/arm64-v8a
cp android-tv/app/build/live-probe-assets/native-probe/arm64-v8a/libherrinarmprobe.so android-tv/app/build/live-probe-jni/arm64-v8a/
