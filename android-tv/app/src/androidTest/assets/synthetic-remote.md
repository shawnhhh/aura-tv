# Original remote-key seek fixture

`synthetic-remote.mp4` repeats the repository's original synthetic H.264/AAC
fixture six times without transcoding. It contains no film footage, source
credentials or third-party media. The 36-second duration allows real injected
D-pad keys to prove +10/-10 seconds without hitting the six-second source boundary.

Reproduce with FFmpeg:

```sh
ffmpeg -stream_loop 5 -i synthetic.mp4 -map 0 -c copy synthetic-remote.mp4
```

- Input SHA-256: `1f056310bcc4e983079bbd5a0f23211d513ebe874f836e8969120bad667b558f`
- Output SHA-256: `7283706941d109c6a7b878946ddaa0f2526312a167c243bf995fdd22becb2ef4`
- Observed: 320×180, 24 fps, duration 36.106608 seconds.
