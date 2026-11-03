"""Offline integrity check for pinned FongMi source; no code is executed."""
from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
FILES = {
    "api/loader/BaseLoader.java": "a4129fc79a16c2920907d184c8e264dd49435cb3",
    "api/loader/JarLoader.java": "c7cab88da3bd189f0b73af8af73f5ad65e6a378a",
    "player/parse/ParseJob.java": "36f6dc832798358c2c4d40c9937863733f37ea1e",
    "server/process/Proxy.java": "994ad408a82bd1625e460657d4620d0776c99684",
}
SOURCE = ROOT / "vendor/fongmi-tv/app/src/main/java/com/fongmi/android/tv"

def verify():
    for relative, expected in FILES.items():
        path = SOURCE / relative
        contents = path.read_bytes()
        actual = hashlib.sha1(
            b"blob " + str(len(contents)).encode() + b"\0" + contents
        ).hexdigest()
        assert actual == expected, f"FongMi source mismatch: {relative}"
    assert (ROOT / "LICENSE-GPL-3.0.md").is_file()
    print(f"PASS: {len(FILES)} pinned FongMi source files match upstream Git blobs")

if __name__ == "__main__":
    verify()
