"""Inspect authentic source-built MPV and FFmpeg AARs without assuming TV ABI compatibility.

The upstream candidate source revision is tracked in dependencies.json. This
checker verifies packaging and SHA-256 evidence; it never patches or generates
native binaries and cannot certify playback or FongMi 5.8.7 compatibility.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile


EXPECTED = {
    "mpvplayer": {"libmpv.so", "libplayer.so"},
    "decoder_ffmpeg": {"libavcodec.so", "libavformat.so"},
}
ABIS = {"arm64-v8a", "armeabi-v7a"}


def inspect(directory):
    result = {"status": "SOURCE_BUILT_NATIVE_CANDIDATE_NOT_TV_ABI_PROOF",
              "artifacts": [], "errors": []}
    for component, libraries in EXPECTED.items():
        artifacts = sorted(directory.glob(f"**/*{component}*release*.aar"))
        if len(artifacts) != 1:
            result["errors"].append(f"{component}: expected one real release AAR, got {len(artifacts)}")
            continue
        path = artifacts[0]
        item = {"module": component, "file": str(path),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        result["artifacts"].append(item)
        try:
            with zipfile.ZipFile(path) as archive:
                with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as jar:
                    classes = [p for p in jar.namelist() if p.endswith(".class")]
                    if not classes:
                        raise ValueError("empty Java implementation")
                    item["class_count"] = len(classes)
                native = {abi: {Path(p).name for p in archive.namelist()
                                if p.startswith("jni/" + abi + "/") and p.endswith(".so")}
                          for abi in sorted(ABIS)}
            item["native_abis"] = {k: len(v) for k, v in native.items()}
            for abi in ABIS:
                missing = libraries - native.get(abi, set())
                if missing:
                    result["errors"].append(f"{component}/{abi}: missing {sorted(missing)}")
        except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
            result["errors"].append(f"{component}: {exc}")
    if result["errors"]:
        result["status"] = "NATIVE_CANDIDATE_BUILD_OR_PACKAGE_FAILED"
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = inspect(args.directory)
    text = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    if args.output:
        args.output.write_text(text)
    print(text)
    if report["errors"]:
        parser.exit(2, "Source-built AAR packaging validation failed\n")
