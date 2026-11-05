"""Offline APK source boundary contract; never execute Spider/vendor code."""
from pathlib import Path
import re

LEGACY = ("SpiderProxyPlayback", "SpiderLoopbackProxy", "HerrinSpiderHlsProxyRewrite", "SpiderDirectPlayback")


def without_comments(code):
    # Preserve quoted URLs so // inside a literal cannot hide later code.
    tokens = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27|/\*[\s\S]*?\*/|//[^\n]*'
    return re.sub(tokens, lambda match: "" if match[0].startswith(("/*", "//")) else match[0], code)


def violations(source_root):
    """Check all production source sets, including debug/release and Java."""
    found = []
    for path in sorted(source_root.rglob("*")):
        if path.suffix not in {".kt", ".java"}:
            continue
        source_set = path.relative_to(source_root).parts[0]
        if source_set in {"test", "androidTest", "liveAndroidTest"}:
            continue
        # Comments may document the removed implementation.
        code = without_comments(path.read_text())
        if path.stem in LEGACY or any(re.search(r"\b" + name + r"\b", code) for name in LEGACY):
            found.append(str(path.relative_to(source_root)))
    return found


def verify():
    found = violations(Path(__file__).resolve().parents[1] / "app/src")
    assert not found, f"Legacy playback in production source sets: {found}"
    print("PASS: no legacy playback implementation/reference in production source sets")


if __name__ == "__main__":
    verify()
