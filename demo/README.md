# Demo video

`customer360-demo.mp4` (about 3.5 min, 1080p, narrated) shows the project end to end. Almost everything on screen is real:
the dashboard scenes are recorded from the running system while real load runs, and the terminal scenes replay output captured
from real runs (`data/parity.txt`, `data/api.json`). Only the architecture and summary slides are drawn.

| Scene | What is real |
|---|---|
| 4 Phase 1 CDC | `sybase/gen-load.sh --orders` places orders in Sybase while the live dashboard is recorded |
| 5 Parity | a full `phase2/run.sh parity all` run (16 of 16) |
| 6 Resilience | GridGain node 3 is really killed and restarted under REST traffic; the script waits for the dashboard to show 2 nodes, holds it, then restarts |
| 7 REST API | real `curl` calls against the running API |
| 8 Dashboard | CDC load and REST traffic at the same time |

Voice-over is the macOS `say` voice "Daniel" (synthetic). Everything is built with Chrome (DevTools screencast), `ffmpeg` and `say`, no extra installs.

## Rebuild

```bash
cd demo
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --remote-debugging-port=9333 \
    --user-data-dir=/tmp/demo-chrome --hide-scrollbars --no-first-run about:blank &
python3 -m http.server 8099 --bind 127.0.0.1 &        # serves scenes/index.html
python3 make.py            # all scenes (about 6 min); or e.g.  python3 make.py s4 s6  to redo some
```

The whole stack must be running. Live scenes **change real data**: they place orders in Sybase and restart `gridgain3`.
Run `python3 make.py` with scene names to rebuild only those; with no arguments it re-records everything.
Temporary frames are deleted after each clip (the disk was nearly full while this was built).
