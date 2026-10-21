# Demo video

> **Not yet re-recorded on the `mariadb-replaces-sybase` branch.** The video and its narration (`data/narration.json`, `scenes/`, captured outputs in `data/`) still show the Sybase ASE version of the project, as on `main`. `make.py` already points at `mariadb/gen-load.sh`, but the narration, the scene text and the captured parity output need updating before a rebuild.

`customer360-demo.mp4` (about 4.5 min, 1080p, narrated) shows both sources end to end. Almost everything on screen is real:
the dashboard scenes are recorded from the running system while real load runs, and the terminal scenes replay output captured
from real runs (`data/parity.txt`, `data/parity-oracle.txt`, `data/hub-sql*.txt`, `data/api2.json`). The architecture, source,
SCN-race and summary slides are drawn.

| Scene | What is real |
|---|---|
| 4 Phase 1 CDC | `sybase/gen-load.sh --orders` and `oracle/gen-load.sh` change both sources while the live dashboard is recorded |
| 5 Oracle SCN race | animated explanation of the commit-SCN window design |
| 6 Parity | full parity runs: 16 of 16 (Sybase) and 30 of 30 (Oracle) |
| 7 Hub SQL | `hub/run.sh` joins Sybase and Oracle data in GridGain |
| 8 Hub API | real `curl` calls: hub view, flight impact, booking, validation error |
| 9 Resilience | GridGain node 3 is really killed and restarted under REST traffic and both loads |
| 10 Dashboard | both CDC loads and REST traffic at the same time |

Voice-over is the neural voice [Kokoro-82M](https://github.com/thewh1teagle/kokoro-onnx) (default `af_heart`; set `VOICE=bf_emma` for UK English),
run in Docker. Build the image with `docker build -t kokoro-tts tts` and download `kokoro-v1.0.onnx` and `voices-v1.0.bin` from the
kokoro-onnx `model-files-v1.0` release into `demo/tts/kokoro/` (about 350 MB, not committed). Edit `data/narration.json` and delete
`audio/<id>.wav` to re-voice a scene. (Earlier versions used macOS `say`, then Piper.)

## Rebuild

```bash
cd demo
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --remote-debugging-port=9333 \
    --user-data-dir=/tmp/demo-chrome --hide-scrollbars --no-first-run about:blank &
python3 -m http.server 8099 --bind 127.0.0.1 &        # serves scenes/index.html
python3 make.py            # all scenes (about 10 min); or e.g.  python3 make.py s4 s9  to redo some
```

The whole stack must be running (both sources, 3 GridGain nodes, API, both CDC readers). Live scenes **change real data**: they
generate load in both sources and restart `gridgain3`. With no arguments `make.py` re-records everything.
Temporary frames are deleted after each clip.
