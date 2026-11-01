#!/usr/bin/env python3
"""Fail-closed preflight for FongMi/TV native AAR inputs (not a playback test).

Expected archive layout is derived from FongMi/TV
buildSrc/src/main/groovy/com/fongmi/gradle/MpvNativeLibraries.groovy
at TV commit 5e191aaced194e5cac5668cc91908ecb65336222.
The TV upstream keeps libffmpeg.so in the *decoder* AAR, not the mpv AAR.
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
MPV_AAR = "lib-mpvplayer-release.aar"
DECODER_AAR = "lib-decoder-ffmpeg-release.aar"
AAR_NAMES = (MPV_AAR, DECODER_AAR)
MPV_LIBS = ("libmpv.so", "libplayer.so", "libc++_shared.so")
DECODER_LIBS = ("libffmpeg.so", "libffmpegJNI.so")
SPLIT_FFMPEG = re.compile(r"^lib(?:av|sw)[^.]*\.so(?:\.\d+)*$")
MAX_ELF_BYTES = 128 * 1024 * 1024


def media_api_errors(media_source):
    source_dir = media_source / "libraries"
    if not source_dir.is_dir():
        return ["Missing FongMi/media libraries source directory"]
    names = {p.stem for p in source_dir.rglob("*") if p.suffix in (".java", ".kt")}
    return ["Missing custom Media3 API: " + name for name in MEDIA_APIS if name not in names]


def elf_info_from_readelf(blob):
    """Check dynamic dependencies and actual ELF class/machine."""
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


def read_aar(kit, name, errors):
    path = kit / name
    if not path.is_file():
        errors.append("Missing native AAR: " + name)
        return {}
    contents = {}
    try:
        with ZipFile(path) as archive:
            members = archive.infolist()
            names = [m.filename for m in members]
            if len(names) != len(set(names)):
                errors.append(name + ": duplicate ZIP entries")
                return {}
            if "classes.jar" not in names:
                errors.append(name + ": missing classes.jar")
            else:
                with ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
                    if name == DECODER_AAR and not any(
                        x.endswith("/FfmpegAudioRenderer.class") for x in classes.namelist()
                    ):
                        errors.append(name + ": missing Media3 FFmpeg audio renderer")
            for member in members:
                if not (member.filename.startswith("jni/") and member.filename.endswith(".so")):
                    continue
                if member.file_size <= 0 or member.file_size > MAX_ELF_BYTES:
                    errors.append(name + ": invalid native size: " + member.filename)
                    continue
                contents[member.filename] = archive.read(member)
            bad = [p for p in contents if SPLIT_FFMPEG.match(p.rsplit("/", 1)[-1])]
            if bad:
                errors.extend(name + ": split FFmpeg library packaged: " + p for p in sorted(bad))
    except (BadZipFile, OSError, ValueError, RuntimeError, EOFError) as exc:
        errors.append(name + ": invalid archive (" + type(exc).__name__ + ")")
    return contents


def aar_errors(kit, abis=ABIS, dependency_reader=elf_info_from_readelf):
    errors = []
    mpv = read_aar(kit, MPV_AAR, errors)
    decoder = read_aar(kit, DECODER_AAR, errors)
    for abi in abis:
        required = (
            (MPV_AAR, mpv, MPV_LIBS),
            (DECODER_AAR, decoder, DECODER_LIBS),
        )
        for archive_name, binaries, names in required:
            for lib in names:
                if "jni/" + abi + "/" + lib not in binaries:
                    errors.append(abi + ": missing " + lib + " in " + archive_name)
        for archive_name, binaries, _ in required:
            for path, blob in binaries.items():
                if not path.startswith("jni/" + abi + "/"):
                    continue
                lib = path.rsplit("/", 1)[-1]
                try:
                    needed, elf_class, machine = dependency_reader(blob)
                except (RuntimeError, ValueError, OSError) as exc:
                    errors.append(path + ": cannot inspect ELF (" + str(exc) + ")")
                    continue
                expected_class, expected_machine = (
                    ("ELF64", "AArch64") if abi == "arm64-v8a" else ("ELF32", "ARM")
                )
                if elf_class != expected_class or machine != expected_machine:
                    errors.append(path + ": wrong ELF architecture: " + elf_class + " / " + machine)
                split = [libname for libname in needed if SPLIT_FFMPEG.match(libname)]
                errors.extend(path + ": incompatible split FFmpeg dependency: " + x for x in split)
                must_depend_on_ffmpeg = lib in ("libmpv.so", "libplayer.so", "libffmpegJNI.so", "libffmpegDoviJNI.so")
                if must_depend_on_ffmpeg and "libffmpeg.so" not in needed:
                    errors.append(path + ": missing required libffmpeg.so dependency")
                if lib == "libplayer.so" and "libmpv.so" not in needed:
                    errors.append(path + ": missing required libmpv.so dependency")
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kit-dir", type=Path, required=True, help="Candidate AAR directory (never auto-distributed)")
    parser.add_argument("--media-source", type=Path, required=True, help="Pinned FongMi/media source checkout")
    parser.add_argument("--output", type=Path, help="Optional JSON evidence path")
    args = parser.parse_args(argv)
    errors = media_api_errors(args.media_source) + aar_errors(args.kit_dir)
    result = {
        "status": "BLOCKED" if errors else "INPUTS_STRUCTURALLY_VALID_NOT_RUNTIME_VERIFIED",
        "errors": errors,
        "scope": "Local inputs only; no runtime/decoder verification",
        "required_abis": list(ABIS),
    }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 2 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
