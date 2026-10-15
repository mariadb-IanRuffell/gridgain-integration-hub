# Demo video

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

Voice-over is the neural voice [Piper](https://github.com/rhasspy/piper) "Alba" (UK female), run in Docker (`eclipse-temurin:17-jre`
image, Linux arm64 Piper build). The engine and model are not committed: download the Piper `piper_linux_aarch64` release into
`demo/tts/linux/piper/` and `en_GB-alba-medium.onnx` plus its `.json` from the `rhasspy/piper-voices` Hugging Face repo
as `demo/tts/alba.onnx` and `demo/tts/alba.onnx.json`; create `demo/tts/out/`. Edit `data/narration.json` and delete
`audio/<id>.wav` to re-voice a scene.

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
