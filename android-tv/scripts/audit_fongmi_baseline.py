"""Audit the whole pinned checkout. This is source provenance, never playback proof."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "vendor/fongmi-core"
UPSTREAM = CORE / "upstream"


def git(*args):
    return subprocess.check_output(["git", "-C", str(UPSTREAM), *args])


def audit():
    lock = json.loads((CORE / "baseline.lock.json").read_text())
    commit = git("rev-parse", "HEAD").decode().strip()
    if commit != lock["commit"]:
        raise RuntimeError("Upstream commit differs from lock")
    if git("status", "--porcelain", "--untracked-files=all"):
        raise RuntimeError("Upstream checkout is modified")
    inventory = json.loads((CORE / "upstream-files.json").read_text())
    tree = []
    for record in git("ls-tree", "-rz", "HEAD").split(b"\0"):
        if not record:
            continue
        metadata, path = record.split(b"\t", 1)
        mode, kind, oid = metadata.decode().split()
        name = path.decode()
        tree.append({"path": name, "mode": mode, "type": kind, "git_blob": oid})
        if kind == "blob":
            data = (UPSTREAM / name).read_bytes()
            actual = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
            if actual != oid:
                raise RuntimeError("Worktree bytes differ: " + name)
    if tree != inventory:
        raise RuntimeError("Full upstream tree differs from inventory")
    if git("rev-parse", "HEAD^{tree}").decode().strip() != lock["tree"]:
        raise RuntimeError("Upstream tree differs from lock")
    license_hash = hashlib.sha256((UPSTREAM / "LICENSE.md").read_bytes()).hexdigest()
    if license_hash != lock["license_sha256"]:
        raise RuntimeError("License differs from lock")
    print(json.dumps({
        "commit": commit, "tracked_files_verified": len(tree),
        "license": "GPL-3.0", "scope": "SOURCE_PROVENANCE_ONLY",
        "compiled_into_herrin": False, "playable_baseline_verified": False,
        "real_frame_verified": False, "p0_complete": False,
    }, indent=2))


if __name__ == "__main__":
    argparse.ArgumentParser(description=__doc__).parse_args()
    audit()
