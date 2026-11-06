"""Verify the source-built Sardine v0.9 dependency without executing vendor code."""
from pathlib import Path
from verify_fongmi_vendor import git_blob_hash


def verify():
    vendor = Path(__file__).resolve().parents[1] / "vendor/sardine-android"
    entries = {}
    for line in (vendor / "UPSTREAM_SHA1.txt").read_text().splitlines():
        sha, relative = line.split("  ", 1)
        assert relative not in entries and len(sha) == 40
        assert git_blob_hash((vendor / relative).read_bytes()) == sha, relative
        entries[relative] = sha
    sources = {p.relative_to(vendor).as_posix() for p in (vendor / "src/main/java").rglob("*.java")}
    assert len(sources) == 90
    assert sources == {p for p in entries if p.endswith(".java")}
    assert {"LICENSE", "build.gradle", "proguard-rules.pro", "src/main/AndroidManifest.xml"} <= entries.keys()
    assert (vendor / "assets/licenses/sardine-android-LICENSE.txt").read_bytes() == (vendor / "LICENSE").read_bytes()
    assert not list(vendor.rglob("*.jar")) and not list(vendor.rglob("*.aar"))
    print("PASS: Sardine v0.9 90 exact source files, license and upstream build contract")


if __name__ == "__main__":
    verify()
