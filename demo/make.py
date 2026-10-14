#!/usr/bin/env python3
"""Produce the project demo video: real footage of the running system + narrated explanatory scenes.

  python3 make.py            build everything
  python3 make.py s4 s6      only these scenes (then re-run the final assembly)

Needs: the whole stack running (Sybase on llama, GridGain x3, gg-api, gg-cdc), headless Chrome on :9333, the scene server on :8099
(see README.md in this folder). Live scenes perform REAL actions while recording: place orders in Sybase, kill and restart a GridGain node.
"""
import json, os, subprocess, sys, threading, time, urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(ROOT)
os.chdir(ROOT)
SCENES_URL = "http://127.0.0.1:8099/scenes/index.html"
DASH_URL = "http://localhost:8080/"
INJECT = open("inject.js").read()
NARR = {n["id"]: n["text"] for n in json.load(open("data/narration.json"))}
NARR["s6a"] = ("The data lives in a three node cluster, with native persistence. With traffic flowing through the REST A P I, "
               "I will kill one node. The cluster drops to two nodes, requests keep succeeding, and nothing is lost.")
NARR["s6b"] = "When the node comes back, it rejoins with its own data."
LEAD = 0.6   # seconds of picture before the voice starts


def sh(cmd, **kw):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, **kw)


def audio_len(name):
    return float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", f"audio/{name}.aiff"],
                                capture_output=True, text=True).stdout)


def ensure_audio(name):
    if not os.path.exists(f"audio/{name}.aiff"):
        subprocess.run(["say", "-v", "Daniel", "-r", "170", "-o", f"audio/{name}.aiff", NARR[name]], check=True)
    return audio_len(name)


def nodes_online():
    try:
        return json.load(urllib.request.urlopen("http://localhost:8080/api/v1/dashboard/summary", timeout=3))["cluster"]["nodes"]
    except Exception:
        return None


def js(expr):
    return expr


def record(name, url, dur, events=None, actions=None):
    """Record `url` for `dur` seconds. events: [(t, js)] run in the page; actions: [(t, callable)] run here. Returns t0 (epoch)."""
    ev = [{"at": t, "js": j} for t, j in (events or [])]
    json.dump(ev, open(f"data/ev-{name}.json", "w"))
    p = subprocess.Popen(["node", "record.mjs", name, url, str(dur), f"data/ev-{name}.json"], stdout=subprocess.PIPE, text=True)
    for line in p.stdout:                      # wait for the recorder to start capturing
        if line.startswith("RECORDING"):
            break
    t0 = time.time()
    threads = []
    for t, fn in (actions or []):
        def run(t=t, fn=fn):
            time.sleep(max(0, t0 + t - time.time()))
            fn(t0)
        th = threading.Thread(target=run, daemon=True); th.start(); threads.append(th)
    rest = p.stdout.read()
    p.wait()
    for th in threads:
        th.join(timeout=5)
    print("  ", rest.strip())
    return t0


def build_clip(name, dur, voices):
    """frames -> clip with narration. voices: [(audio_name, start_seconds)]"""
    mf = f"frames/{name}/manifest.txt"
    inputs, filt, mix = ["-f", "concat", "-safe", "0", "-i", mf], [], []
    for i, (a, start) in enumerate(voices, start=1):
        inputs += ["-i", f"audio/{a}.aiff"]
        ms = int(start * 1000)
        filt.append(f"[{i}:a]adelay={ms}|{ms}[a{i}]")
        mix.append(f"[a{i}]")
    filt.append("".join(mix) + f"amix=inputs={len(voices)}:normalize=0,apad,afade=t=in:d=0.3,afade=t=out:st={dur-0.4:.2f}:d=0.4[aout]")
    vf = (f"fps=30,scale=1920:1080:flags=lanczos,tpad=stop_mode=clone:stop_duration=3,trim=duration={dur},setpts=PTS-STARTPTS,"
          f"fade=t=in:d=0.35,fade=t=out:st={dur-0.4:.2f}:d=0.4,format=yuv420p")
    cmd = ["ffmpeg", "-v", "error", "-y"] + inputs + ["-filter_complex", f"[0:v]{vf}[vout];" + ";".join(filt),
           "-map", "[vout]", "-map", "[aout]", "-t", f"{dur}", "-c:v", "libx264", "-crf", "20", "-preset", "veryfast",
           "-c:a", "aac", "-b:a", "160k", "-ar", "44100", f"clips/{name}.mp4"]
    subprocess.run(cmd, check=True)
    subprocess.run(f"rm -rf frames/{name}", shell=True)
    print(f"   clip {name}: {dur:.1f}s")


def html_scene(sid, dur, query="", audio=None):
    d = dur
    record(sid, f"{SCENES_URL}?scene={query or sid}&d={d}", d + 0.3)
    build_clip(sid, d, [(audio or sid, LEAD)])


def live_setup(view, zoom, caption, rate=None, extra=""):
    r = f"__rate({rate});" if rate is not None else ""
    return INJECT + f"__view('{view}', {zoom}); __cap({json.dumps(caption)});{r}{extra} 'ok'"


# ----------------------------------------------------------------------------- scenes
def s1(): html_scene("s1", max(6.0, ensure_audio("s1") + 3.5))
def s2(): html_scene("s2", ensure_audio("s2") + 2.4)
def s3(): html_scene("s3", ensure_audio("s3") + 2.6)


def s4():   # phase 1 live: place orders in Sybase while recording the dashboard
    dur = ensure_audio("s4") + 7.0
    gen = lambda t0: sh(f"cd '{PROJ}/sybase' && ./gen-load.sh --orders 12 5")
    record("s4", DASH_URL, dur,
           events=[(0.2, live_setup("charts", 1.1, "Phase 1 · new orders are placed in Sybase")),
                   (17.0, "__view('tables', 1.3); __cap('Row counts converge once the burst ends'); 'ok'")],
           actions=[(0.5, gen)])
    build_clip("s4", dur, [("s4", LEAD)])


def s5(): html_scene("s5", ensure_audio("s5") + 2.6, "term&kind=parity")


def s6():   # cluster resilience: kill node 3 under REST traffic, hold the 2-node state, restart it, note when it rejoined
    a, b = ensure_audio("s6a"), ensure_audio("s6b")
    dur = 72.0
    marks = {}
    kill = lambda t0: (sh("docker kill gridgain3"), marks.update(kill=time.time() - t0))

    def restart(t0):
        while nodes_online() != 2 and time.time() - t0 < 45:     # wait until the dashboard actually shows 2 nodes
            time.sleep(0.5)
        marks["two"] = time.time() - t0
        time.sleep(9)                                              # hold the degraded state so the viewer can see it
        sh("docker start gridgain3")
        marks["start"] = time.time() - t0
        while time.time() - t0 < dur - 2:
            if nodes_online() == 3:
                marks["rejoin"] = time.time() - t0
                return
            time.sleep(0.5)

    record("s6", DASH_URL, dur,
           events=[(0.2, live_setup("charts", 1.1, "3 of 3 GridGain nodes online", rate=20, extra="__nodes();")),
                   (9.0, "window.__pend = 'killing node 3 ...'; 'ok'")],
           actions=[(9.0, kill), (9.5, restart)])
    print("   marks:", {k: round(v, 1) for k, v in marks.items()})
    rejoin = marks.get("rejoin", 50.0)
    final = min(dur, max(rejoin + 9.0, LEAD + a + 1.0 + b + 3.0))   # end shortly after the node is back
    build_clip("s6", final, [("s6a", LEAD), ("s6b", min(max(rejoin + 1.0, LEAD + a + 1.0), final - b - 1.5))])


def s7(): html_scene("s7", ensure_audio("s7") + 6.0, "term&kind=api")


def s8():   # both loads at once on the dashboard
    dur = ensure_audio("s8") + 6.0
    gen = lambda t0: sh(f"cd '{PROJ}/sybase' && ./gen-load.sh 12 100")
    record("s8", DASH_URL, dur, events=[(0.2, live_setup("charts", 1.1, "Live: Sybase changes + REST requests", rate=50))], actions=[(0.5, gen)])
    build_clip("s8", dur, [("s8", LEAD)])


def s9(): html_scene("s9", ensure_audio("s9") + 2.4)


ORDER = ["s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9"]


def assemble():
    clips = [f"clips/{s}.mp4" for s in ORDER if os.path.exists(f"clips/{s}.mp4")]
    with open("clips/list.txt", "w") as f:
        f.write("".join(f"file '{os.path.basename(c)}'\n" for c in clips))
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", "clips/list.txt", "-c", "copy",
                    "-movflags", "+faststart", "customer360-demo.mp4"], check=True)
    d = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", "customer360-demo.mp4"],
                             capture_output=True, text=True).stdout)
    print(f"customer360-demo.mp4: {d:.0f}s from {len(clips)} clips, {os.path.getsize('customer360-demo.mp4') / 1e6:.1f} MB")


if __name__ == "__main__":
    want = sys.argv[1:] or ORDER
    for s in ORDER:
        if s in want:
            print(f"== {s}")
            globals()[s]()
    assemble()
