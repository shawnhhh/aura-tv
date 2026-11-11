#!/usr/bin/env bash
set -euo pipefail
kernel_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
upstream_root="$kernel_root/../vendor/fongmi-core/upstream"
case "${1:-}" in
  catvod) tasks=(':catvod:bundleDebugAar') ;;
  quickjs) tasks=(':quickjs:bundleDebugAar') ;;
  nodejs) tasks=(':nodejs:bundleDebugAar') ;;
  thunder) tasks=(':thunder:bundleDebugAar') ;;
  python-bridge) tasks=(':chaquo:bundleDebugAar') ;;
  *) echo 'Usage: build-source-modules.sh catvod|quickjs|nodejs|thunder|python-bridge' >&2; exit 2 ;;
esac
# Use the pinned upstream wrapper and original build scripts. No APK generation,
# package/signature changes, native substitution, or silently skipped feature.
exec bash "$upstream_root/gradlew" -p "$kernel_root" "${tasks[@]}"
