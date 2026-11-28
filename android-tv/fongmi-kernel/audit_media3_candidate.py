"""Check immutable, hash-pinned Media3 *candidate* AARs against original TV APIs.

This only audits publicly published bytes. It never makes them a dependency or
calls a playback-ready result. Strict version, transitive dependency, full
license and ARM validation remain independent prerequisites for integration.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent


def inspect(manifest_path, output_path=None):
    item = json.loads(manifest_path.read_text())
    repo, revision, version = item["repository"], item["commit"], item["media3_version"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Candidate must use a full immutable commit")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid repository identifier")
    report = {"repository": repo, "commit": revision, "version": version,
              "tv_commit": item["upstream_tv_commit"], "modules": [],
              "missing_classes": [], "missing_ui_methods": [],
              "errors": [], "status": "AUDIT_PENDING_NOT_PROVEN"}
    for module in item["modules"]:
        name, checksum = module["artifact"], module["sha256"]
        if not re.fullmatch(r"media3-[a-z0-9-]+", name) or not re.fullmatch(r"[0-9a-f]{64}", checksum):
            raise ValueError("Invalid pinned module")
        path = f"third_party/maven/androidx/media3/{name}/{version}/{name}-{version}.aar"
        url = f"https://raw.githubusercontent.com/{repo}/{revision}/{path}"
        audit = {"artifact": name, "expected_sha256": checksum,
                 "source_path": path, "actual_sha256": None, "classes": 0}
        report["modules"].append(audit)
        try:
            with urllib.request.urlopen(url, timeout=45) as response:
                # Single artifact limit prevents unbounded resource consumption.
                binary = response.read(16 * 1024 * 1024 + 1)
            if len(binary) > 16 * 1024 * 1024:
                raise ValueError("Artifact exceeds audit size limit")
            actual = hashlib.sha256(binary).hexdigest()
            audit["actual_sha256"] = actual
            if actual != checksum:
                raise ValueError("Published AAR checksum mismatch")
            with zipfile.ZipFile(io.BytesIO(binary)) as aar:
                classes_jar = aar.read("classes.jar")
            with zipfile.ZipFile(io.BytesIO(classes_jar)) as jar:
                classes = {path[:-6] for path in jar.namelist() if path.endswith(".class")}
                audit["classes"] = len(classes)
                required = item["required_classes"].get(name, [])
                absent = sorted(set(required) - classes)
                report["missing_classes"].extend(absent)
                if name == "media3-ui":
                    with tempfile.TemporaryDirectory() as directory:
                        target = Path(directory) / "classes.jar"
                        target.write_bytes(classes_jar)
                        # The exact FongMi adapter invokes PlayerView.setBackendPlayer.
                        result = subprocess.run(
                            ["javap", "-public", "-classpath", str(target),
                             "androidx.media3.ui.PlayerView"], capture_output=True,
                            text=True, check=False, timeout=25)
                        if result.returncode != 0 or item["required_ui_method"] not in result.stdout:
                            report["missing_ui_methods"].append(item["required_ui_method"])
        except (OSError, ValueError, KeyError, zipfile.BadZipFile,
                urllib.error.URLError, subprocess.TimeoutExpired) as error:
            report["errors"].append(f"{name}: {type(error).__name__}: {error}")
    if report["errors"]:
        report["status"] = "FETCH_OR_BINARY_AUDIT_BLOCKED"
    elif report["missing_classes"] or report["missing_ui_methods"]:
        report["status"] = "CANDIDATE_API_INCOMPATIBLE"
    else:
        report["status"] = "CANDIDATE_API_PRESENT_VERSION_AND_LICENSE_UNPROVEN"
    if output_path:
        output_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=ROOT / "media3-candidate.json")
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()
    outcome = inspect(args.manifest, args.output)
    print(json.dumps(outcome, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
