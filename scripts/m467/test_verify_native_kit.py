"""Offline regression for M4.67 native input gate. No private AARs or network."""
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location("verify_native_kit", Path(__file__).with_name("verify_native_kit.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def write_aar(path, files):
    with ZipFile(path, "w") as archive:
        for name, data in files.items():
            archive.writestr(name, data)


def build_kit(root, *, missing_ffmpeg_abi=False):
    mpv = {"classes.jar": b"placeholder"}
    ffmpeg_classes = io.BytesIO()
    with ZipFile(ffmpeg_classes, "w") as jar:
        jar.writestr("androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.class", b"synthetic")
    ffmpeg = {"classes.jar": ffmpeg_classes.getvalue()}
    for abi in verifier.ABIS:
        for lib in verifier.MPV_LIBS:
            mpv["jni/" + abi + "/" + lib] = b"fake-elf"
        if not (missing_ffmpeg_abi and abi == verifier.ABIS[-1]):
            ffmpeg["jni/" + abi + "/libffmpegJNI.so"] = b"fake-elf"
    write_aar(root / verifier.AAR_NAMES[0], mpv)
    write_aar(root / verifier.AAR_NAMES[1], ffmpeg)


class NativeKitTests(unittest.TestCase):
    def test_missing_custom_media3_apis_fail_closed(self):
        with tempfile.TemporaryDirectory() as d:
            source = Path(d) / "libraries" / "other"
            source.mkdir(parents=True)
            (source / "DiskPreloadManager.java").write_text("class DiskPreloadManager {}")
            errors = verifier.media_api_errors(Path(d))
            self.assertEqual(7, len(errors))
            self.assertTrue(any("DecoderManager" in e for e in errors))

    def test_declared_media_apis_present(self):
        with tempfile.TemporaryDirectory() as d:
            source = Path(d) / "libraries"
            source.mkdir()
            for name in verifier.MEDIA_APIS:
                (source / (name + ".java")).write_text("class " + name + "{}")
            self.assertEqual([], verifier.media_api_errors(Path(d)))

    def test_missing_aar_and_abis_are_blocked(self):
        with tempfile.TemporaryDirectory() as d:
            errors = verifier.aar_errors(Path(d), dependency_reader=lambda _: [])
            self.assertTrue(any("Missing native AAR" in e for e in errors))
            self.assertTrue(any("missing mpv ELF" in e for e in errors))

    def test_complete_synthetic_layout_is_structurally_accepted(self):
        with tempfile.TemporaryDirectory() as d:
            build_kit(Path(d))
            self.assertEqual([], verifier.aar_errors(Path(d), dependency_reader=lambda _: []))

    def test_split_ffmpeg_is_rejected_even_with_all_files(self):
        with tempfile.TemporaryDirectory() as d:
            build_kit(Path(d))
            errors = verifier.aar_errors(Path(d), dependency_reader=lambda _: ["libavcodec.so", "libswscale.so"])
            self.assertTrue(any("incompatible split FFmpeg" in e for e in errors))

    def test_incomplete_second_abi_cannot_pass(self):
        with tempfile.TemporaryDirectory() as d:
            build_kit(Path(d), missing_ffmpeg_abi=True)
            errors = verifier.aar_errors(Path(d), dependency_reader=lambda _: [])
            self.assertTrue(any("armeabi-v7a: missing FFmpeg audio JNI" in e for e in errors))

    def test_corrupt_aar_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / verifier.AAR_NAMES[0]).write_bytes(b"not-a-zip")
            errors = verifier.aar_errors(Path(d), dependency_reader=lambda _: [])
            self.assertTrue(any("invalid archive" in e for e in errors))

    def test_no_fake_success_on_missing_fongmi_sources(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            build_kit(root)
            self.assertEqual(2, verifier.main(["--kit-dir", str(root), "--media-source", str(root),
                                               "--output", str(root / "report.json")]))
            report = json.loads((root / "report.json").read_text())
            self.assertEqual("BLOCKED", report["status"])


if __name__ == "__main__":
    unittest.main()
