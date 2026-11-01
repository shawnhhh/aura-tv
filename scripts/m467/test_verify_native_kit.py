"""No-network regression for upstream-aligned M4.67 native input gate."""
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location(
    "verify_native_kit", Path(__file__).with_name("verify_native_kit.py")
)
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def jar_with(*classes):
    buffer = io.BytesIO()
    with ZipFile(buffer, "w") as archive:
        for name in classes:
            archive.writestr(name, b"synthetic-only")
    return buffer.getvalue()


def write_aar(path, files):
    with ZipFile(path, "w") as archive:
        for name, data in files.items():
            archive.writestr(name, data)


def fake_elf(blob, needed=()):
    arm64 = b"arm64-v8a" in blob
    lib = blob.decode().rsplit(":", 1)[-1]
    expected = []
    if lib in ("libmpv.so", "libplayer.so", "libffmpegJNI.so", "libffmpegDoviJNI.so"):
        expected.append("libffmpeg.so")
    if lib == "libplayer.so":
        expected.append("libmpv.so")
    return expected + list(needed), "ELF64" if arm64 else "ELF32", "AArch64" if arm64 else "ARM"


def build_kit(root, *, missing_decoder_abi=False, split_library=False, dovi=False):
    mpv = {"classes.jar": jar_with("org/example/MpvPlayer.class")}
    ffmpeg = {"classes.jar": jar_with("androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.class")}
    for abi in verifier.ABIS:
        for lib in verifier.MPV_LIBS:
            mpv["jni/" + abi + "/" + lib] = ("synthetic:" + abi + ":" + lib).encode()
        if split_library:
            mpv["jni/" + abi + "/libavcodec.so"] = ("synthetic:" + abi + ":libavcodec.so").encode()
        for lib in verifier.DECODER_LIBS:
            if not (missing_decoder_abi and abi == verifier.ABIS[-1] and lib == "libffmpegJNI.so"):
                ffmpeg["jni/" + abi + "/" + lib] = ("synthetic:" + abi + ":" + lib).encode()
        if dovi:
            ffmpeg["jni/" + abi + "/libffmpegDoviJNI.so"] = ("synthetic:" + abi + ":libffmpegDoviJNI.so").encode()
    write_aar(root / verifier.MPV_AAR, mpv)
    write_aar(root / verifier.DECODER_AAR, ffmpeg)


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

    def test_missing_aars_are_blocked(self):
        with tempfile.TemporaryDirectory() as d:
            errors = verifier.aar_errors(Path(d), dependency_reader=fake_elf)
            self.assertTrue(any("Missing native AAR" in e for e in errors))

    def test_correct_upstream_layout_is_accepted_structurally(self):
        with tempfile.TemporaryDirectory() as d:
            build_kit(Path(d))
            self.assertEqual([], verifier.aar_errors(Path(d), dependency_reader=fake_elf))

    def test_ffmpeg_belongs_in_decoder_aar_not_mpv_aar(self):
        with tempfile.TemporaryDirectory() as d:
            build_kit(Path(d))
            with ZipFile(Path(d) / verifier.MPV_AAR) as a:
                self.assertNotIn("jni/arm64-v8a/libffmpeg.so", a.namelist())
            self.assertEqual([], verifier.aar_errors(Path(d), dependency_reader=fake_elf))

    def test_dolby_vision_optional_native_jni_is_inspected(self):
        with tempfile.TemporaryDirectory() as d:
            build_kit(Path(d), dovi=True)
            self.assertEqual([], verifier.aar_errors(Path(d), dependency_reader=fake_elf))

    def test_missing_decoder_abi_cannot_pass(self):
        with tempfile.TemporaryDirectory() as d:
            build_kit(Path(d), missing_decoder_abi=True)
            errors = verifier.aar_errors(Path(d), dependency_reader=fake_elf)
            self.assertTrue(any("armeabi-v7a: missing libffmpegJNI.so" in e for e in errors))

    def test_packaged_split_ffmpeg_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            build_kit(Path(d), split_library=True)
            errors = verifier.aar_errors(Path(d), dependency_reader=fake_elf)
            self.assertTrue(any("split FFmpeg library packaged" in e for e in errors))

    def test_dynamic_split_ffmpeg_is_rejected_even_with_complete_aars(self):
        with tempfile.TemporaryDirectory() as d:
            build_kit(Path(d))
            errors = verifier.aar_errors(
                Path(d), dependency_reader=lambda blob: fake_elf(blob, ["libavcodec.so", "libswscale.so"])
            )
            self.assertTrue(any("incompatible split FFmpeg dependency" in e for e in errors))

    def test_wrong_elf_machine_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            build_kit(Path(d))
            errors = verifier.aar_errors(Path(d), dependency_reader=lambda blob: ([], "ELF32", "ARM"))
            self.assertTrue(any("wrong ELF architecture" in e for e in errors))

    def test_mpv_player_requires_real_link_to_libmpv(self):
        with tempfile.TemporaryDirectory() as d:
            build_kit(Path(d))
            errors = verifier.aar_errors(
                Path(d),
                dependency_reader=lambda blob: (
                    [x for x in fake_elf(blob)[0] if x != "libmpv.so"],
                    fake_elf(blob)[1], fake_elf(blob)[2],
                ),
            )
            self.assertTrue(any("missing required libmpv.so dependency" in e for e in errors))

    def test_unreadable_native_binary_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            build_kit(Path(d))
            errors = verifier.aar_errors(
                Path(d), dependency_reader=lambda blob: (_ for _ in ()).throw(ValueError("invalid ELF"))
            )
            self.assertTrue(any("cannot inspect ELF" in e for e in errors))

    def test_corrupt_archive_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / verifier.MPV_AAR).write_bytes(b"not-a-zip")
            errors = verifier.aar_errors(Path(d), dependency_reader=fake_elf)
            self.assertTrue(any("invalid archive" in e for e in errors))

    def test_missing_fongmi_sources_never_reports_success(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            build_kit(root)
            self.assertEqual(2, verifier.main([
                "--kit-dir", str(root), "--media-source", str(root),
                "--output", str(root / "report.json"),
            ]))
            report = json.loads((root / "report.json").read_text())
            self.assertEqual("BLOCKED", report["status"])


if __name__ == "__main__":
    unittest.main()
