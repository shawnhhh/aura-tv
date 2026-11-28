"""Audit native ARM release AARs built from pinned FongMi/media sources.

This verifies real package bytes, Java classes and ARM ELF machine types.
It is *not* a FongMi TV 5.8.7 Media3/API compatibility or playback test.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
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


def original_native_imports(upstream: Path):
    roots = [upstream / "app/src/main/java", upstream / "app/src/leanback/java"]
    if any(not root.is_dir() for root in roots):
        raise ValueError("Pinned original FongMi TV source missing")
    found = set()
    for root in roots:
        for file in root.rglob("*.java"):
            found.update(re.findall(
                r"^\s*import\s+(androidx\.media3\.(?:mpvplayer|decoder\.ffmpeg)\.[\w.]+);",
                file.read_text(), re.M))
    return found


def inspect(directory: Path, upstream: Path | None = None):
    result = {
        "status": "SOURCE_BUILT_NATIVE_CANDIDATE_NOT_TV_ABI_PROOF",
        "artifacts": [],
        "errors": [],
        "missing_original_tv_classes": [],
    }
    implementation_classes = set()
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
                    implementation_classes.update(
                        c[:-6] for c in classes if c.endswith(".class"))
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
    if upstream is not None:
        try:
            required = original_native_imports(upstream)
            result["original_tv_native_import_count"] = len(required)
            result["missing_original_tv_classes"] = sorted(
                required - {name.replace("/", ".") for name in implementation_classes})
            if not required:
                result["errors"].append("No original TV native imports found; pinned source invalid")
        except (OSError, ValueError) as error:
            result["errors"].append(str(error))
    if result["errors"]:
        result["status"] = "NATIVE_CANDIDATE_BUILD_OR_PACKAGE_FAILED"
    elif result["missing_original_tv_classes"]:
        result["status"] = "SOURCE_BUILT_NATIVE_CANDIDATE_TV_API_INCOMPATIBLE"
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--upstream", type=Path, default=None,
                        help="Pinned FongMi/TV checkout; require exact imported native classes")
    args = parser.parse_args()
    result = inspect(args.directory, args.upstream)
    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(text)
    print(text)
    if result["errors"] or result["missing_original_tv_classes"]:
        parser.exit(2, "Source-built native AAR packaging or original TV API audit failed\n")
