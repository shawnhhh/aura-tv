#!/usr/bin/env bash
# Original synthetic Herrin clip only; no provider media or credentials.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
source_clip="$root/android-tv/app/src/androidTest/assets/synthetic.mp4"
fixture_dir="$root/android-tv/app/src/androidTest/assets/m467"
mkdir -p "$fixture_dir"
ffmpeg -hide_banner -loglevel error -y -i "$source_clip" -map 0 -c copy "$fixture_dir/synthetic.mkv"
ffmpeg -hide_banner -loglevel error -y -i "$source_clip" -map 0:v -map 0:a:0 -c copy -hls_time 2 -hls_list_size 0 -hls_segment_filename "$fixture_dir/segment-%02d.ts" "$fixture_dir/child.m3u8"
ffmpeg -hide_banner -loglevel error -y -i "$source_clip" -map 0:v -map 0:a:0 -c copy -f dash -seg_duration 2 "$fixture_dir/synthetic.mpd"
cat > "$fixture_dir/master.m3u8" <<'PLAYLIST'
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=500000
child.m3u8
PLAYLIST
cat > "$fixture_dir/synthetic.srt" <<'CAPTION'
1
00:00:00,200 --> 00:00:05,800
M467 caption decoded
CAPTION
ffmpeg -hide_banner -loglevel error -y -i "$fixture_dir/synthetic.srt" "$fixture_dir/synthetic.ass"
cp "$fixture_dir/synthetic.ass" "$fixture_dir/synthetic.ssa"
ffmpeg -hide_banner -loglevel error -y -i "$fixture_dir/synthetic.srt" "$fixture_dir/synthetic.vtt"
for clip in "$source_clip" "$fixture_dir/synthetic.mkv" "$fixture_dir/master.m3u8" "$fixture_dir/synthetic.mpd"; do
    ffprobe -v error -show_entries stream=codec_name,codec_type -show_entries format=duration -of json "$clip"
done
