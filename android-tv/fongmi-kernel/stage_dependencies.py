"""Stage verified original FongMi AARs into an immutable local Maven repository.

Use only after collecting real binaries, source commits, SHA-256 hashes, license
texts and build instructions. This does not build, fetch or fabricate media APIs.
"""
import argparse
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
from verify_dependencies import ROOT, UPSTREAM, confined, verify


def coordinate(entry):
    artifact_id = Path(entry["path"]).stem
    version = entry["source_commit"][:12] + "." + entry["sha256"][:12]
    return "tv.herrin.original", artifact_id, version


def stage(directory: Path, lock: Path, output: Path):
    # Fail closed *before* modifying the output.
    verification = verify(directory, lock)
    entries = json.loads(lock.read_text())["artifacts"]
    allowed = (ROOT / "build").resolve()
    target = output.resolve()
    target.relative_to(allowed)
    if target == allowed:
        raise ValueError("Cannot stage at the build root")
    temp = target.with_name(target.name + ".stage")
    if temp.exists():
        shutil.rmtree(temp)
    temp.mkdir(parents=True, exist_ok=True)
    try:
        manifest = []
        for entry in entries:
            root = UPSTREAM if entry.get("origin") == "upstream" else directory
            original = confined(root, entry["path"])
            group, artifact, version = coordinate(entry)
            package = temp / group.replace(".", "/") / artifact / version
            package.mkdir(parents=True, exist_ok=True)
            destination = package / (artifact + "-" + version + ".aar")
            shutil.copyfile(original, destination)
            # The published Maven artifact must be byte-identical to the
            # verified source archive, including the upstream hook/tvbus inputs.
            import hashlib
            if hashlib.sha256(destination.read_bytes()).hexdigest() != entry["sha256"]:
                raise ValueError("Staged archive checksum mismatch: " + artifact)
            project = ET.Element("project", {"xmlns": "http://maven.apache.org/POM/4.0.0"})
            ET.SubElement(project, "modelVersion").text = "4.0.0"
            ET.SubElement(project, "groupId").text = group
            ET.SubElement(project, "artifactId").text = artifact
            ET.SubElement(project, "version").text = version
            ET.SubElement(project, "packaging").text = "aar"
            ET.ElementTree(project).write(package / (artifact + "-" + version + ".pom"),
                                          encoding="utf-8", xml_declaration=True)
            manifest.append({"coordinate": group + ":" + artifact + ":" + version + "@aar",
                             "sha256": entry["sha256"],
                             "source_repository": entry["source_repository"],
                             "source_commit": entry["source_commit"]})
        (temp / "locked-artifacts.json").write_text(
            json.dumps({"status": verification["status"], "artifacts": manifest},
                       ensure_ascii=False, indent=2) + "\n")
        if target.exists():
            shutil.rmtree(target)
        temp.rename(target)
    finally:
        if temp.exists():
            shutil.rmtree(temp)
    return {"status": "LOCKED_ORIGINAL_MAVEN_READY_NOT_PLAYBACK_PROOF",
            "artifacts": manifest, "repository": str(target)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--lock", type=Path, default=ROOT / "dependencies.json")
    parser.add_argument("--output", type=Path, default=ROOT / "build/locked-original-maven")
    args = parser.parse_args()
    try:
        print(json.dumps(stage(args.directory, args.lock, args.output), ensure_ascii=False))
    except (ValueError, OSError, KeyError) as error:
        parser.exit(2, str(error) + "\n")
