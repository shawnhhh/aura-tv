"""Tiny synthetic ZIP/ELF fixtures test collection rules; never shipping binaries."""
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from collect_native_candidate import collect, EXPECTED as NAMES
from verify_native_candidate import inspect, elf_machine, EXPECTED


def elf(abi):
    # Only validates ELF identification in tests. Not an executable library.
    header = bytearray(24)
    header[:4] = b"\x7fELF"
    header[4], header[5] = (2, 1) if abi == "arm64-v8a" else (1, 1)
    struct.pack_into("<H", header, 18, 183 if abi == "arm64-v8a" else 40)
    return bytes(header)


def fixture(path, module, invalid_abi=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as aar:
        mem = io.BytesIO()
        with zipfile.ZipFile(mem, "w") as jar:
            jar.writestr("example/Test.class", b"fixture")
        aar.writestr("classes.jar", mem.getvalue())
        for abi in ("arm64-v8a", "armeabi-v7a"):
            for lib in EXPECTED[module][1]:
                aar.writestr("jni/" + abi + "/" + lib,
                             elf("arm64-v8a" if invalid_abi else abi))


class NativeCandidateTests(unittest.TestCase):
    def test_collects_from_shared_buildout_not_only_module_folder(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "vendor-media"
            destination = Path(tmp) / "collected"
            for module, (gradle, source, name) in NAMES.items():
                fixture(root / "buildout" / gradle / "outputs/aar" / name, module)
            report = collect(root, destination)
            self.assertEqual(len(report["artifacts"]), 2)
            self.assertEqual(inspect(destination)["errors"], [])

    def test_collector_rejects_duplicate_build_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "vendor-media"
            destination = Path(tmp) / "collected"
            for module, (gradle, source, name) in NAMES.items():
                fixture(root / "buildout" / gradle / "outputs/aar" / name, module)
            fixture(root / "libraries/mpvplayer/build/outputs/aar/lib-mpvplayer-release.aar",
                    "mpvplayer")
            with self.assertRaisesRegex(ValueError, "expected exactly one"):
                collect(root, destination)

    def test_rejects_mislabeled_or_wrong_architecture(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            fixture(directory / "lib-mpvplayer-release.aar", "mpvplayer",
                    invalid_abi=True)
            fixture(directory / "lib-decoder-ffmpeg-release.aar", "decoder_ffmpeg")
            report = inspect(directory)
            self.assertEqual(report["status"], "NATIVE_CANDIDATE_BUILD_OR_PACKAGE_FAILED")
            self.assertTrue(any("armeabi-v7a" in e and "ELF" in e for e in report["errors"]))

    def test_original_tv_missing_class_is_explicit_incompatibility(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output = root / "aar"
            output.mkdir()
            fixture(output / "lib-mpvplayer-release.aar", "mpvplayer")
            fixture(output / "lib-decoder-ffmpeg-release.aar", "decoder_ffmpeg")
            upstream = root / "tv"
            source = upstream / "app/src/main/java"
            leanback = upstream / "app/src/leanback/java"
            source.mkdir(parents=True)
            leanback.mkdir(parents=True)
            (source / "Engine.java").write_text(
                "import androidx.media3.mpvplayer.MpvDecoderMode;\n"
            )
            outcome = inspect(output, upstream)
            self.assertEqual(
                outcome["status"], "SOURCE_BUILT_NATIVE_CANDIDATE_TV_API_INCOMPATIBLE"
            )
            self.assertIn(
                "androidx.media3.mpvplayer.MpvDecoderMode",
                outcome["missing_original_tv_classes"]
            )

    def test_rejects_missing_ffmpeg(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            fixture(directory / "lib-mpvplayer-release.aar", "mpvplayer")
            report = inspect(directory)
            self.assertTrue(any("decoder_ffmpeg" in e for e in report["errors"]))


if __name__ == "__main__":
    unittest.main()
