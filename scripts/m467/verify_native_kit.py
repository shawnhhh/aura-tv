#!/usr/bin/env python3
"""Fail-closed verifier for optional, independently built FongMi native inputs.

Never treat a file named *.aar as proof of compatible mpv/FFmpeg support.
This tool validates *inputs only*, not actual Android decoder compatibility.
"""
import argparse
import io
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import BadZipFile, ZipFile

MEDIA_APIS = (
    "DecoderManager", "LibassPlaybackSession", "LibassSubtitleController",
    "ContentTypeResolvingMediaSource", "SubtitleTranscriptSession",
    "ProgressiveIsoCache", "SecondaryTextTrackSelector",
)
ABIS = ("arm64-v8a", "armeabi-v7a")
MPV_LIBS = frozenset(("libmpv.so", "libplayer.so", "libffmpeg.so"))
AAR_NAMES = ("lib-mpvplayer-release.aar", "lib-decoder-ffmpeg-release.aar")
SPLIT_FFMPEG = re.compile(r"^lib(?:av|sw)[^.]*\.so(?:\.\d+)*$")


def media_api_errors(media_source):
    source_dir = media_source / "libraries"
    if not source_dir.is_dir():
        return ["Missing FongMi/media libraries source directory"]
    names = {p.stem for p in source_dir.rglob("*") if p.suffix in (".java", ".kt")}
    return ["Missing custom Media3 API: " + name for name in MEDIA_APIS if name not in names]


def elf_info_from_readelf(blob):
    """Read ELF class, architecture and DT_NEEDED; filenames alone prove nothing."""
    executable = shutil.which("readelf")
    if not executable:
        raise RuntimeError("readelf is required to verify native dynamic dependencies")
    with tempfile.TemporaryDirectory(prefix="herrin-native-") as d:
        p = Path(d) / "lib.so"
        p.write_bytes(blob)
        result = subprocess.run([executable, "-d", str(p)], capture_output=True, text=True)
        header = subprocess.run([executable, "-h", str(p)], capture_output=True, text=True)
    if result.returncode or header.returncode:
        raise ValueError("Invalid ELF or unreadable header/dynamic section")
    cls = re.search(r"Class:\s+(ELF(?:32|64))", header.stdout)
    machine = re.search(r"Machine:\s+([^\n]+)", header.stdout)
    if not cls or not machine:
        raise ValueError("Unreadable ELF class or machine")
    needed = re.findall(r"Shared library: \[([^\]]+)\]", result.stdout)
    return needed, cls.group(1), machine.group(1).strip()


def aar_errors(kit, abis=ABIS, dependency_reader=elf_info_from_readelf):
    errors = []
    archives = {}
    for aar_name in AAR_NAMES:
        path = kit / aar_name
        if not path.is_file():
            errors.append("Missing native AAR: " + aar_name)
            continue
        try:
            with ZipFile(path) as archive:
                if archive.testzip() is not None:
                    raise BadZipFile("Damaged AAR entry")
                entries = set(archive.namelist())
                if "classes.jar" not in entries:
                    errors.append(aar_name + ": missing classes.jar")
                elif aar_name.startswith("lib-decoder"):
                    with ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
                        if not any(x.endswith("/FfmpegAudioRenderer.class") for x in classes.namelist()):
                            errors.append(aar_name + ": not a Media3 FFmpeg audio renderer")
                archives[aar_name] = {name: archive.read(name) for name in entries if name.startswith("jni/") and name.endswith(".so")}
        except (BadZipFile, OSError, ValueError) as exc:
            errors.append(aar_name + ": invalid archive (" + type(exc).__name__ + ")")

    mpv = archives.get(AAR_NAMES[0], {})
    ffmpeg = archives.get(AAR_NAMES[1], {})
    for abi in abis:
        expected = {"jni/" + abi + "/" + name for name in MPV_LIBS}
        errors.extend(abi + ": missing mpv ELF " + p.split("/")[-1] for p in sorted(expected - set(mpv)))
        if "jni/" + abi + "/libffmpegJNI.so" not in ffmpeg:
            errors.append(abi + ": missing FFmpeg audio JNI")
        for path, blob in mpv.items():
            if not path.startswith("jni/" + abi + "/"):
                continue
            try:
                needed, elf_class, machine = dependency_reader(blob)
                expected_class, expected_machine = (
                    ("ELF64", "AArch64") if abi == "arm64-v8a" else ("ELF32", "ARM")
                )
                if elf_class != expected_class or machine != expected_machine:
                    errors.append(path + ": wrong ELF architecture: " + elf_class + " / " + machine)
                for lib in needed:
                    if SPLIT_FFMPEG.match(lib):
                        errors.append(path + ": incompatible split FFmpeg dependency: " + lib)
            except (RuntimeError, ValueError) as exc:
                errors.append(path + ": " + str(exc))
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kit-dir", type=Path, required=True, help="Untrusted candidate AAR directory")
    parser.add_argument("--media-source", type=Path, required=True, help="Source checkout of pinned FongMi/media fork")
    parser.add_argument("--output", type=Path, help="Optional JSON evidence file")
    args = parser.parse_args(argv)
    errors = media_api_errors(args.media_source) + aar_errors(args.kit_dir)
    result = {
        "status": "BLOCKED" if errors else "INPUTS_STRUCTURALLY_VALID_NOT_RUNTIME_VERIFIED",
        "errors": errors,
        "scope": "Only validates local inputs; never proves TCL/mpv/FFmpeg playback",
        "required_abis": list(ABIS),
    }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 2 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
