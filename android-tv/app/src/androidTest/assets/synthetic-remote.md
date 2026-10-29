# Original remote-key seek fixture

`synthetic-remote.mp4` repeats the repository's original synthetic H.264/AAC
fixture six times and re-encodes the original video/audio with continuous sample
timestamps. It contains no film footage, source credentials or third-party media.
The 36-second duration allows real injected D-pad keys to prove +10/-10 seconds
without hitting the six-second source boundary.

The video timestamps are generated from the frame index at exactly 24 fps. Audio
sample timestamps are regenerated separately. This avoids AAC padding gaps from
stream-copy looping changing the video's average frame rate as reported by Media3.

Reproduce with FFmpeg:

```sh
ffmpeg -y -stream_loop 5 -i synthetic.mp4 -map 0:v:0 -map '0:a?' \
  -vf 'setpts=N/(24*TB)' -r 24 -fps_mode:v cfr -t 36 \
  -c:v libx264 -preset veryfast -crf 24 -pix_fmt yuv420p \
  -g 48 -keyint_min 48 -sc_threshold 0 -af 'asetpts=N/SR/TB' \
  -c:a aac -b:a 64k -movflags +faststart synthetic-remote.mp4
```

- Input SHA-256: `1f056310bcc4e983079bbd5a0f23211d513ebe874f836e8969120bad667b558f`
- Output SHA-256: `babf3e077485766e4bbd9a2f0aef0c206c4732476cf6f9dac3421f9018e6daaa`
- Verified with ffprobe: 320×180, `r_frame_rate=24/1`, `avg_frame_rate=24/1`,
  864 video frames, video/container duration 36.000000 seconds.
- The existing actual Media3 `24 fps`, +10/-10 second, progress-text and nonblack
  screenshot assertions remain unchanged.
