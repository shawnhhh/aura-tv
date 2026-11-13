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


def verify_manager_route(panel, session):
    # The committed UI now owns HerrinPlayerSession. Keep the sole-adapter
    # boundary through that wrapper; requiring its constructor in the panel
    # would incorrectly demand the removed direct ownership path.
    assert panel.count('HerrinPlayerSession(') == 1, 'Panel must own exactly one player session'
    assert 'HerrinPlaybackAdapter(' not in panel, 'Panel bypasses session ownership'
    assert session.count('HerrinPlaybackAdapter(') == 1, 'Session must contain exactly one current-player adapter'
    assert re.search(r'private\s+val\s+legacy\s*=\s*if\s*\(\s*original\s*==\s*null\s*\)\s*\{[^}]*HerrinPlaybackAdapter\(', session), \
        'Current-player adapter must be exclusive with the original-core lease'


def verify():
    source = Path(__file__).resolve().parents[1] / "app/src"
    found = violations(source)
    assert not found, f"Legacy playback in production source sets: {found}"
    creators = []
    for path in source.rglob("*"):
        if path.suffix not in {".kt", ".java"} or path.relative_to(source).parts[0] in {
            "test", "androidTest", "liveAndroidTest"
        }:
            continue
        if re.search(r"\bExoPlayer\s*\.\s*Builder\s*\(", without_comments(path.read_text())):
            creators.append(path.name)
    assert creators == ["FongMiExoPlayerFactory.kt"], f"Competing Exo creation paths: {creators}"
    panel = without_comments((source / "main/java/tv/herrin/app/AuthorizedPlaybackPanel.kt").read_text())
    session = without_comments((source / "main/java/tv/herrin/app/HerrinPlayerSession.kt").read_text())
    verify_manager_route(panel, session)
    assert not re.search(r"\bplayer\s*\.\s*(?:setMediaItem|prepare|release|setPlaybackSpeed)\s*\(", panel), \
        "Player preparation/release bypasses the sole production manager"
    assert "PlaybackTransport.cycle(player" not in panel, "Track choices bypass manager restoration"
    print("PASS: no legacy playback implementation/reference in production source sets")
    print("PASS: one Exo builder, production adapter owns preparation and release")


if __name__ == "__main__":
    verify()
