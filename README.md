# Control Loop Music

A small Python/PyQt6 GUI that plays one music file from argv 1 and loops only the selected period.

Run:

```bash
python3 src/main.py /path/to/music-file.mp3
```

The range bar starts at the full file length. Drag the left or right handle to change the loop start and end points while the file plays. The red line shows the current playback position and can be dragged to seek within the loop.

Click the raised `Loop:` range text to type a range directly, such as `00:00-00:28`, then press Enter.

When Qt can decode the selected file for analysis, the bar also shows the audio waveform inside the progress track.

Waveform drawing is split into `src/waveform_renderers.py`. Pass a different `WaveformRenderer` implementation to `LoopRangeBar(...)` to switch oscillogram drawing style.
