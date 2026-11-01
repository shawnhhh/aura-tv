#!/usr/bin/env python3
"""Regenerate the declaration-complete player map using the JDK AST inventory."""
import argparse
import csv
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("upstream", type=Path)
parser.add_argument("--java", default="java")
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
script = Path(__file__).with_name("InventoryPlayerMethods.java")
inventory = subprocess.check_output([args.java, str(script), str(args.upstream)], text=True)
rows = []
base = "android-tv/app/src/main/java/"
for line in inventory.splitlines():
    source, number, signature = line.split("\t")
    name = signature.split("(")[0]
    target, method = "—", "—"
    status, test = "BLOCKED_CURRENT_MEDIA3_ABI", "NOT_EXECUTABLE_CURRENT_DEPENDENCY_SET"
    if source == "player/media/ConcatenatingUrl.java":
        target, method = base + "com/fongmi/android/tv/" + source, signature
        status = "DIRECT_SOURCE"
        test = "M467FongMiMediaContractTest.concatenationPreservesSignedSourceAndDurationAndUtf8Length;malformedSegmentsAreRejectedBeforeOpeningAnySource"
    elif source == "player/track/TrackUtil.java":
        target, method = base + "com/fongmi/android/tv/" + source, signature
        status = "DIRECT_SOURCE_HOST_NULL_CHECK"
        test = "M467FongMiMediaContractTest.subtitleFormatMatchesUpstreamAcrossSrtAssSsaVttAndUnknown;M467FongMiTrackRestoreTest (3 cases);PlaybackTransportTest.realRemoteSeekAndDecodedAudioSubtitleTracks"
    elif source == "player/exo/ExoMediaSourceFactory.java" and name in {
            "createMediaSource", "getSupportedTypes", "setDrmSessionManagerProvider",
            "setLoadErrorHandlingPolicy", "createDefaultExtractorsFactory"}:
        target, method = base + "com/fongmi/android/tv/" + source, name
        status = "MINIMAL_HOST_ABI_ADAPTATION"
        if name == "createDefaultExtractorsFactory":
            status = "PARTIAL_CUSTOM_MATROSKA_FLAG_UNAVAILABLE"
        test = "M467EngineContainerTest (8 cases);PlaybackTransportTest"
    elif source == "player/exo/ExoUtil.java" and name in {
            "buildPlayer", "buildLoadControl", "buildRenderersFactory", "buildTrackSelector"}:
        target, method = base + "tv/herrin/app/FongMiExoPlayerFactory.kt", "create"
        status, test = "PARTIAL_PUBLISHED_ABI_ADAPTATION", "M467EngineContainerTest (8 cases)"
    elif source == "player/media/MediaItemFactory.java" and name in {
            "from", "buildSubConfig", "buildRequestMetadata", "toBundle"}:
        target, method = base + "tv/herrin/app/FongMiExoPlayerFactory.kt", "mediaItem"
        status, test = "PARTIAL_HOST_MODEL_ADAPTATION", "M467EngineContainerTest (8 cases)"
    rows.append([source, number, signature, target, method, status, test])
with args.output.open("w", newline="") as stream:
    writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
    writer.writerow(["upstream_file", "upstream_line", "upstream_method", "herrin_file", "herrin_method", "status", "automated_case"])
    writer.writerows(rows)
print(f"Wrote {len(rows)} method declarations to {args.output}")
