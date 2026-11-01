#!/usr/bin/env python3
"""Read-only ABI/provenance audit. Exit 2 means upstream parity is blocked, not passed."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def command(*args, cwd=None):
    return subprocess.run(args, cwd=cwd, check=True, text=True, capture_output=True).stdout.strip()


def audit(tv, media, mpv):
    required_api = [
        "DecoderManager", "LibassPlaybackSession", "LibassSubtitleController",
        "ContentTypeResolvingMediaSource", "SubtitleTranscriptSession",
        "ProgressiveIsoCache", "DiskPreloadManager", "SecondaryTextTrackSelector",
    ]
    source_files = list((media / "libraries").rglob("*.java"))
    source_names = {p.stem for p in source_files}
    api = {name: name in source_names for name in required_api}
    aars = {name: (tv / "app/libs" / name).is_file() for name in [
        "lib-mpvplayer-release.aar", "lib-decoder-ffmpeg-release.aar"
    ]}
    libraries = []
    for path in sorted((media / "libraries/mpvplayer/src/main/jniLibs").glob("*/*.so")):
        dynamic = command("readelf", "-d", str(path))
        header = command("readelf", "-h", str(path))
        needed = re.findall(r"Shared library: \[([^]]+)\]", dynamic)
        libraries.append({
            "path": str(path.relative_to(media)),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "elf_class": re.search(r"Class:\s+(\S+)", header).group(1),
            "machine": re.search(r"Machine:\s+(.+)", header).group(1),
            "needed": needed,
            "split_ffmpeg_dependency": any(n.startswith("libav") or n.startswith("libsw") for n in needed),
        })
    verifier = (tv / "buildSrc/src/main/groovy/com/fongmi/gradle/MpvNativeLibraries.groovy").read_text()
    unified_required = "libffmpeg.so" in verifier and "SPLIT_FFMPEG" in verifier
    blockers = []
    if not all(api.values()): blockers.append("Public media branch lacks current TV Java APIs")
    if not all(aars.values()): blockers.append("TV's required native AAR inputs are absent")
    if unified_required and any(x["split_ffmpeg_dependency"] for x in libraries):
        blockers.append("Public media prebuilts use split FFmpeg; current TV requires unified libffmpeg.so")
    if not (mpv / "buildscripts/sdk/android-ndk-r30/toolchains/llvm/prebuilt").is_dir():
        blockers.append("Current mpv-android NDK r30 toolchain is not installed")
    return {
        "schema": 1, "status": "BLOCKED" if blockers else "INPUTS_PRESENT_NOT_RUNTIME_VALIDATED",
        "tv_sha": command("git", "rev-parse", "HEAD", cwd=tv),
        "media_sha": command("git", "rev-parse", "HEAD", cwd=media),
        "mpv_android_sha": command("git", "rev-parse", "HEAD", cwd=mpv),
        "required_java_api_present": api, "required_aars_present": aars,
        "current_tv_requires_unified_ffmpeg": unified_required,
        "public_media_native_libraries": libraries, "blockers": blockers,
        "redistributed_native_libraries": [],
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("tv", type=Path)
    parser.add_argument("media", type=Path)
    parser.add_argument("mpv_android", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.tv, args.media, args.mpv_android)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"status": result["status"], "blockers": result["blockers"],
                      "report": str(args.output)}, indent=2))
    raise SystemExit(2 if result["blockers"] else 0)
