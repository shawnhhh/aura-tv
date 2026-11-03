"""Verify immutable FongMi upstream source files without executing vendor code."""
from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "vendor/fongmi-tv"
MANIFEST = VENDOR / "UPSTREAM_SHA1.txt"

def git_blob_hash(data):
    return hashlib.sha1(
        b"blob " + str(len(data)).encode("ascii") + b"\0" + data
    ).hexdigest()

def verify():
    assert (ROOT / "LICENSE-GPL-3.0.md").is_file(), "GPL license missing"
    checked = 0
    paths = set()
    for line in MANIFEST.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        sha, relative = line.split("  ", 1)
        assert len(sha) == 40 and relative.startswith("app/src/main/java/")
        assert relative not in paths, f"Duplicate staged file: {relative}"
        paths.add(relative)
        actual = git_blob_hash((VENDOR / relative).read_bytes())
        assert actual == sha, f"FongMi upstream source mismatch: {relative}"
        checked += 1
    assert checked >= 22, f"Incomplete FongMi source import: only {checked}"
    print(f"PASS: {checked} exact pinned upstream FongMi files; not a playback test")

if __name__ == "__main__":
    verify()
