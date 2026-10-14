// Helpers injected into the live dashboard while recording: caption overlay, layout views, traffic generator.
(() => {
  if (window.__demo) return; window.__demo = true;
  const st = document.createElement('style');
  st.textContent = '#__cap{position:fixed;left:50%;bottom:30px;transform:translateX(-50%);background:rgba(10,17,32,.93);color:#e8eefc;' +
    'border:2px solid #2a3b60;border-radius:18px;padding:14px 36px;font:600 34px -apple-system,Helvetica,Arial,sans-serif;z-index:9999;' +
    'box-shadow:0 8px 30px rgba(0,0,0,.35);transition:opacity .4s;white-space:nowrap}';
  document.head.appendChild(st);
  const cap = document.createElement('div'); cap.id = '__cap'; cap.style.opacity = 0; document.body.appendChild(cap);
  window.__cap = t => { if (!t) { cap.style.opacity = 0; return; } cap.textContent = t; cap.style.opacity = 1; };
  window.__view = (v, z) => {   // 'charts': KPIs + graphs   'tables': KPIs + table sizes
    const tbl = document.querySelector('#tbl').closest('section'), tools = document.querySelector('.tools'), grid = document.querySelector('.grid2');
    tbl.style.display = v === 'charts' ? 'none' : '';
    tools.style.display = 'none';
    grid.style.display = v === 'tables' ? 'none' : '';
    document.documentElement.style.zoom = z; window.scrollTo(0, 0); window.dispatchEvent(new Event('resize'));
  };
  window.__rate = n => { const r = document.getElementById('rate'); r.value = String(n); r.dispatchEvent(new Event('change')); };
  window.__nodes = () => { let prev = 3, note = '', until = 0;
    setInterval(async () => {
      try {
        const n = (await (await fetch('/api/v1/dashboard/summary')).json()).cluster.nodes, now = Date.now();
        if (n < prev) { window.__pend = ''; note = 'a node was lost, requests keep succeeding'; until = now + 120000; }
        if (n > prev) { note = 'node rejoined with its own data'; until = now + 12000; }
        prev = n;
        __cap(n + ' of 3 GridGain nodes online' + (window.__pend ? ' · ' + window.__pend : (now < until ? ' · ' + note : '')));
      } catch (e) {}
    }, 1000); };
})();
