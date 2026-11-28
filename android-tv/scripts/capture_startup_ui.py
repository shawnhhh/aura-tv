"""Obtain a fresh, well-formed UIAutomator snapshot; UI acceptance stays separate."""
import argparse
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path


def capture(remote, target, run=subprocess.run, attempts=3):
    target = Path(target)
    # Never let a previous successful dump survive a failed new observation.
    target.unlink(missing_ok=True)
    for attempt in range(1, attempts + 1):
        fresh = f"{remote}.{attempt}.xml"
        run(["adb", "shell", "rm", "-f", fresh], capture_output=True)
        dumped = run(["adb", "shell", "uiautomator", "dump", fresh], capture_output=True)
        pulled = run(["adb", "exec-out", "cat", fresh], capture_output=True)
        prefix = target.with_name(f"{target.stem}-attempt-{attempt}")
        prefix.with_suffix(".log").write_bytes(dumped.stdout + dumped.stderr + pulled.stderr)
        prefix.with_suffix(".xml").write_bytes(pulled.stdout)
        if dumped.returncode != 0 or pulled.returncode != 0:
            continue
        try:
            root = ET.fromstring(pulled.stdout)
        except ET.ParseError:
            continue
        if root.tag != "hierarchy" or root.find(".//node") is None:
            continue
        target.write_bytes(pulled.stdout)
        return
    raise RuntimeError(f"No fresh UIAutomator hierarchy after {attempts} transport attempts: {target}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("remote")
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    capture(args.remote, args.target)
