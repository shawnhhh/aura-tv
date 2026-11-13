# Original 72-second decoder baseline

This is repository-owned synthetic H.264 with two AAC tracks, derived solely
from `../synthetic.mp4`. No film, provider stream or account is involved.
It supplies one continuous timeline for a strict 60-second capability check.
MediaItem repeat of a six-second clip adds renderer transitions and buffering;
that is a separate behavior, not a continuous long-clip baseline.

Input SHA256: `1f056310bcc4e983079bbd5a0f23211d513ebe874f836e8969120bad667b558f`

Output SHA256: `e96d3d073c466240f7a71ca7910f24f7fa08a54a879c484313513c060dacbb0e`

ffprobe: 320x180, H.264, 1728 video frames at 24fps, 72.000s; both AAC tracks 72.000s.

Reproduce from `android-tv/app/src/androidTest/assets`:

```sh
ffmpeg -hide_banner -loglevel error -y -stream_loop 11 -i synthetic.mp4 \
  -map 0:v:0 -map '0:a?' -vf 'setpts=N/(24*TB)' -r 24 -fps_mode:v cfr -t 72 \
  -c:v libx264 -preset veryfast -crf 24 -pix_fmt yuv420p -g 48 -keyint_min 48 \
  -sc_threshold 0 -af 'asetpts=N/SR/TB' -c:a aac -b:a 64k -movflags +faststart \
  playback-diagnostics/baseline-72.mp4
```

The HLS baseline reuses the original `synthetic-segment-00.ts` twelve times
with distinct segment URIs and standard `EXT-X-DISCONTINUITY` boundaries on
one 72-second VOD timeline. It is served only by the existing local fixture
dispatcher. Neither format can satisfy real-provider acceptance.
