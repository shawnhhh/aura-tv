"""Check provenance and APIs of the exact original FongMi media AAR inputs.

--report lists missing inputs without failing. Strict mode blocks compilation
until the original, licensed ARM binaries and matching Media3 APIs are present.
No downloads, fabricated implementations or APK extraction are performed.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parent
UPSTREAM = ROOT.parent / "vendor/fongmi-core/upstream"


def confined(root, name):
    root = root.resolve()
    if not name or Path(name).is_absolute():
        raise ValueError("Absolute or empty artifact path")
    path = (root / name).resolve()
    path.relative_to(root)
    return path


def load_classes(path):
    with zipfile.ZipFile(path) as aar:
        try:
            jar_data = aar.read("classes.jar")
        except KeyError as error:
            raise ValueError("Missing classes.jar: " + path.name) from error
        with zipfile.ZipFile(io.BytesIO(jar_data)) as jar:
            classes = {n[:-6] for n in jar.namelist()
                       if n.endswith(".class") and not n.startswith("META-INF/")}
        native_abis = {abi for abi in ("arm64-v8a", "armeabi-v7a")
                       if any(n.startswith("jni/" + abi + "/") and n.endswith(".so")
                              for n in aar.namelist())}
        return classes, native_abis


def imported_media3():
    imports = set()
    for flavor in ("main", "leanback"):
        base = UPSTREAM / "app" / "src" / flavor / "java"
        if not base.is_dir():
            raise ValueError("Upstream source checkout absent: " + str(base))
        for file in base.rglob("*.java"):
            imports.update(re.findall(r"^\s*import\s+(androidx\.media3\.[\w.]+);",
                                      file.read_text(), re.M))
    return imports


def inspect(directory, lock_path):
    lock = json.loads(lock_path.read_text())
    if not re.fullmatch(r"[0-9a-f]{40}", lock["upstream_commit"]):
        raise ValueError("Upstream revision must be a full commit")
    external = set(lock["required_external_archives"])
    upstream = set(lock["required_upstream_archives"])
    if not external or not upstream or not isinstance(lock["artifacts"], list):
        raise ValueError("Invalid required archive declarations")
    found = set()
    seen_paths = set()
    seen_names = set()
    verified = []
    errors = []
    missing_external = set(external)
    missing_upstream = set(upstream)
    for a in lock["artifacts"]:
        try:
            for field in ("path", "sha256", "source_repository", "source_commit",
                          "version", "license_file", "build_instructions"):
                if not isinstance(a.get(field), str) or not a[field].strip():
                    raise ValueError("Missing provenance field " + field)
            origin = a.get("origin", "external")
            if origin not in ("external", "upstream"):
                raise ValueError("Unknown archive origin")
            if not re.fullmatch(r"[0-9a-f]{64}", a["sha256"]):
                raise ValueError("Invalid SHA-256")
            if not re.fullmatch(r"[0-9a-f]{40}", a["source_commit"]):
                raise ValueError("Unpinned source commit")
            root = UPSTREAM if origin == "upstream" else directory
            path = confined(root, a["path"])
            if path.suffix != ".aar":
                raise ValueError("Not an AAR")
            key = (origin, a["path"])
            if key in seen_paths or path.name in seen_names:
                raise ValueError("Duplicate or colliding archive")
            seen_paths.add(key)
            seen_names.add(path.name)
            if origin == "upstream":
                if a["path"] not in upstream or a["source_commit"] != lock["upstream_commit"]:
                    raise ValueError("Unexpected or mismatched original TV archive")
            elif path.name not in external:
                raise ValueError("Unexpected external archive")
            if not path.is_file():
                raise ValueError("Missing file: " + str(path))
            if hashlib.sha256(path.read_bytes()).hexdigest() != a["sha256"]:
                raise ValueError("Binary checksum mismatch")
            license_path = confined(directory, a["license_file"])
            if not license_path.is_file() or not license_path.read_text().strip():
                raise ValueError("Missing license material")
            classes, native = load_classes(path)
            if not classes:
                raise ValueError("Empty implementation")
            duplicate = found & classes
            if duplicate:
                raise ValueError("Duplicate implementation class: " + sorted(duplicate)[0])
            if origin == "external" and native != {"arm64-v8a", "armeabi-v7a"}:
                raise ValueError("Native ARM32/ARM64 libraries missing")
            found.update(classes)
            verified.append(path.name)
            if origin == "external":
                missing_external.discard(path.name)
            else:
                missing_upstream.discard(a["path"])
        except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
            errors.append(str(a.get("path", "<unnamed>")) + ": " + str(error))
    # Gradle imports every AAR in each directory, so undeclared binaries are unsafe.
    if directory.is_dir():
        extra = {a.name for a in directory.glob("*.aar")} - seen_names
        if extra:
            errors.append("Unpinned AARs: " + ", ".join(sorted(extra)))
    upstream_libs = UPSTREAM / "app/libs"
    if upstream_libs.is_dir():
        extra = {a.name for a in upstream_libs.glob("*.aar")} - {Path(a).name for a in upstream}
        if extra:
            errors.append("Unpinned original TV AARs: " + ", ".join(sorted(extra)))
    try:
        imports = imported_media3()
    except ValueError as error:
        errors.append(str(error))
        imports = set()
    required = {name.replace(".", "/") for name in
                (imports | set(lock.get("required_original_apis", [])))}
    missing_apis = sorted(required - found)
    if not missing_external and not missing_upstream and not errors and missing_apis:
        errors.append("Original media APIs missing: " + ", ".join(missing_apis[:30]))
    status = ("INVALID" if errors else "DEPENDENCY_BLOCKED" if
              missing_external or missing_upstream else
              "BUILD_INPUTS_PRESENT_NOT_PLAYBACK_PROOF")
    return {
        "status": status,
        "verified": sorted(verified),
        "missing_external": sorted(missing_external),
        "missing_upstream": sorted(missing_upstream),
        "media3_import_count": len(imports),
        "missing_media3_api_count": len(missing_apis),
        "errors": errors,
        "upstream_commit": lock["upstream_commit"],
    }


def verify(directory, lock_path):
    result = inspect(directory, lock_path)
    if result["status"] != "BUILD_INPUTS_PRESENT_NOT_PLAYBACK_PROOF":
        raise ValueError("FONGMI_BUILD_INPUTS_BLOCKED: " +
                         json.dumps(result, ensure_ascii=False))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--lock", type=Path, default=ROOT / "dependencies.json")
    parser.add_argument("--report", action="store_true")
    args = parser.parse_args()
    try:
        result = inspect(args.directory, args.lock) if args.report else verify(args.directory, args.lock)
        print(json.dumps(result, sort_keys=True, ensure_ascii=False))
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(2, str(error) + "\n")
