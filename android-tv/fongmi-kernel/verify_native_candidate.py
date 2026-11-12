"""Audit native ARM release AARs built from pinned FongMi/media sources.

This verifies real package bytes, Java classes and ARM ELF machine types.
It is *not* a FongMi TV 5.8.7 Media3/API compatibility or playback test.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile

EXPECTED = {
    "mpvplayer": ("lib-mpvplayer-release.aar", {"libmpv.so", "libplayer.so"}),
    "decoder_ffmpeg": ("lib-decoder-ffmpeg-release.aar", {"libavcodec.so", "libavformat.so"}),
}
ABIS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40)}


def elf_machine(data: bytes):
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF shared library")
    elf_class = data[4]
    endian = data[5]
    if endian not in (1, 2):
        raise ValueError("invalid ELF endian")
    machine = struct.unpack_from("<H" if endian == 1 else ">H", data, 18)[0]
    return elf_class, machine


def inspect(directory: Path):
    result = {
        "status": "SOURCE_BUILT_NATIVE_CANDIDATE_NOT_TV_ABI_PROOF",
        "artifacts": [],
        "errors": [],
    }
    expected_names = {filename for filename, _ in EXPECTED.values()}
    extras = sorted(p.name for p in directory.glob("*.aar") if p.name not in expected_names)
    if extras:
        result["errors"].append("Unexpected AARs: " + ", ".join(extras))
    for module, (filename, libraries) in EXPECTED.items():
        path = directory / filename
        if not path.is_file():
            result["errors"].append(f"{module}: required Gradle output missing: {filename}")
            continue
        item = {
            "module": module, "file": str(path),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
        result["artifacts"].append(item)
        try:
            with zipfile.ZipFile(path) as archive:
                with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as jar:
                    classes = [p for p in jar.namelist() if p.endswith(".class")]
                    if not classes:
                        raise ValueError("empty Java implementation")
                    item["class_count"] = len(classes)
                native = {}
                for abi, (expected_class, expected_machine) in ABIS.items():
                    libs = {
                        Path(n).name: n for n in archive.namelist()
                        if n.startswith("jni/" + abi + "/") and n.endswith(".so")
                    }
                    native[abi] = len(libs)
                    for lib in sorted(libraries - libs.keys()):
                        result["errors"].append(f"{module}/{abi}: missing {lib}")
                    for lib in sorted(libraries & libs.keys()):
                        try:
                            found = elf_machine(archive.read(libs[lib]))
                            if found != (expected_class, expected_machine):
                                raise ValueError(f"ELF type {found}, expected "
                                                 f"{(expected_class, expected_machine)}")
                        except ValueError as error:
                            result["errors"].append(f"{module}/{abi}/{lib}: {error}")
                item["native_abis"] = native
        except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
            result["errors"].append(f"{module}: {error}")
    if result["errors"]:
        result["status"] = "NATIVE_CANDIDATE_BUILD_OR_PACKAGE_FAILED"
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = inspect(args.directory)
    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(text)
    print(text)
    if result["errors"]:
        parser.exit(2, "Source-built native AAR audit failed\n")
