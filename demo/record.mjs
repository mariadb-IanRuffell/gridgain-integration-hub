// Record a web page (real footage) from headless Chrome over the DevTools protocol, no dependencies.
//   node record.mjs <name> <url> <seconds> [events.json]
// events.json: [{"at": 3.5, "js": "document.title"} , ...]   page scripts run at t seconds after load
// Output: frames/<name>/NNNNN.jpg + frames/<name>/manifest.txt (ffmpeg concat list with real frame durations)
import fs from 'node:fs';
const [name, url, secs, eventsFile] = process.argv.slice(2);
const events = eventsFile ? JSON.parse(fs.readFileSync(eventsFile, 'utf8')) : [];
const dir = `frames/${name}`; fs.rmSync(dir, { recursive: true, force: true }); fs.mkdirSync(dir, { recursive: true });
const W = 1920, H = 1080, DSF = 1;

const target = await (await fetch('http://localhost:9333/json/new?about:blank', { method: 'PUT' })).json();
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise(r => ws.addEventListener('open', r));
let id = 0; const pending = new Map(), handlers = new Map();
ws.addEventListener('message', m => {
  const d = JSON.parse(m.data);
  if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); }
  else if (d.method && handlers.has(d.method)) handlers.get(d.method)(d.params);
});
const send = (method, params = {}) => new Promise(r => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const on = (m, f) => handlers.set(m, f);

await send('Page.enable');
await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false });
await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: 'light' }] });
const loaded = new Promise(r => on('Page.loadEventFired', r));
await send('Page.navigate', { url });
await loaded;
await new Promise(r => setTimeout(r, 700));

const frames = []; let n = 0;
on('Page.screencastFrame', async p => {
  const file = `${String(++n).padStart(5, '0')}.jpg`;
  fs.writeFileSync(`${dir}/${file}`, Buffer.from(p.data, 'base64'));
  frames.push({ file, t: p.metadata.timestamp });
  send('Page.screencastFrameAck', { sessionId: p.sessionId });
});
const t0 = Date.now();
console.log('RECORDING');
await send('Page.startScreencast', { format: 'jpeg', quality: 82, maxWidth: W * DSF, maxHeight: H * DSF, everyNthFrame: 1 });
for (const e of events) setTimeout(() => send('Runtime.evaluate', { expression: e.js, awaitPromise: false }), e.at * 1000);
await new Promise(r => setTimeout(r, secs * 1000));
await send('Page.stopScreencast');
await new Promise(r => setTimeout(r, 300));
const elapsed = (Date.now() - t0) / 1000;

// ffmpeg concat list: each frame lasts until the next one; the last until the end of the recording
let list = '';
frames.forEach((f, i) => {
  const next = i + 1 < frames.length ? frames[i + 1].t : f.t + Math.max(0.05, secs - (f.t - frames[0].t));
  list += `file '${f.file}'\nduration ${Math.max(0.01, next - f.t).toFixed(4)}\n`;
});
if (frames.length) list += `file '${frames[frames.length - 1].file}'\n`;
fs.writeFileSync(`${dir}/manifest.txt`, list);
console.log(`${name}: ${frames.length} frames in ${elapsed.toFixed(1)}s (${(frames.length / elapsed).toFixed(1)} fps avg)`);
await fetch(`http://localhost:9333/json/close/${target.id}`);
process.exit(0);
