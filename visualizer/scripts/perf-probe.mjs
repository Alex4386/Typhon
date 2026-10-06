// Performance probe of the 3D view against a running sim-server: frame rate, JS time per frame,
// draw calls/triangles, long tasks and main-thread busy time over a measuring window, plus a
// screenshot. Optionally reports lava-film statistics (cool thin lava cells).
// Usage: node scripts/perf-probe.mjs [url] [--seconds N] [--shot file.png] [--lava] [--view summit|top]
//          [--set '{"showAtmosphere":false}'] [--tiles minTilesBeforeMeasuring]
import { chromium } from 'playwright';

const argv = process.argv.slice(2);
const opt = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 ? argv[i + 1] : dflt;
};
const url = argv.find((a) => a.startsWith('http')) ?? 'http://localhost:8790/?renderer=webgl&debug&quality=medium';
const seconds = Number(opt('--seconds', 20));
const shot = opt('--shot', null);
const view = opt('--view', null);

const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage', '--enable-precise-memory-info'] });
const [vw, vh] = String(opt('--size', '1440x900')).split('x').map(Number);
const page = await browser.newPage({ viewport: { width: vw, height: vh }, deviceScaleFactor: 1 });
page.setDefaultTimeout(600000);
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
await page.goto(url, { waitUntil: 'commit' });

const ev = (fn, arg) => page.evaluate(fn, arg);
for (let i = 0; i < 240; i++) {
  await page.waitForTimeout(1000);
  const b = await ev(() => window.__typhonMessages?.binary ?? 0).catch(() => 0);
  const tiles = await ev(() => Object.keys(window.__typhon?.getState().tileRevision ?? {}).length).catch(() => 0);
  if (b > 40 && tiles > Number(opt('--tiles', 20))) break;
}
await ev(() => window.__typhon.getState().set({ guideOpen: false }));
// extra store settings for A/B runs, e.g. --set '{"showAtmosphere":false}'
const extra = opt('--set', null);
if (extra) await ev((s) => window.__typhon.getState().set(JSON.parse(s)), extra);
if (view === 'top') {
  // --alt: camera height (scene units), --dx/--dy: offset from the first vent (m)
  const cam = { alt: Number(opt('--alt', 6500)), dx: Number(opt('--dx', 0)), dy: Number(opt('--dy', 0)) };
  await ev((c) => {
    const w = window.__typhon.getState().world;
    const v = w.volcanoes[0].vents[0].at;
    window.__typhonCamera.getState().requestCamera({ kind: 'pose', instant: true, pose: { mode: 'orbit', position: [v[0] + c.dx, c.alt, -(v[1] + c.dy) + c.alt * 0.09], heading: 0, pitch: -1.45 } });
  }, cam).catch((e) => errors.push(String(e)));
}
await page.waitForTimeout(8000); // let tiles build

const result = await ev(async (secs) => {
  const perf = window.__typhonPerf;
  let longTasks = 0;
  let longCount = 0;
  const po = new PerformanceObserver((l) => {
    for (const e of l.getEntries()) {
      longTasks += e.duration;
      longCount++;
    }
  });
  try {
    po.observe({ type: 'longtask', buffered: false });
  } catch {}
  const f0 = perf.frames;
  const samples = [];
  const t0 = performance.now();
  // main-thread responsiveness: how late a 50 ms timer fires on average
  let lag = 0;
  let lagN = 0;
  await new Promise((resolve) => {
    const tick = () => {
      const due = performance.now() + 50;
      setTimeout(() => {
        lag += Math.max(0, performance.now() - due);
        lagN++;
        if (performance.now() - t0 < secs * 1000) tick();
        else resolve();
      }, 50);
    };
    tick();
    const sample = setInterval(() => samples.push({ ...perf }), 1000);
    setTimeout(() => clearInterval(sample), secs * 1000);
  });
  po.disconnect();
  const dt = (performance.now() - t0) / 1000;
  const avg = (k) => samples.reduce((a, s) => a + s[k], 0) / Math.max(1, samples.length);
  const st = window.__typhon.getState();
  return {
    seconds: dt,
    framesRendered: perf.frames - f0,
    fps: (perf.frames - f0) / dt,
    cpuMsPerFrame: avg('cpuMs'),
    drawCalls: avg('calls'),
    triangles: avg('triangles'),
    geometries: avg('geometries'),
    programs: avg('programs'),
    longTaskMsPerSec: longTasks / dt,
    longTasks: longCount,
    timerLagMs: lag / Math.max(1, lagN),
    heapMB: performance.memory ? performance.memory.usedJSHeapSize / 1e6 : null,
    entities: Object.keys(st.entities).length,
    dpr: perf.dpr,
    markersDrawn: perf.entities,
    tiles: Object.keys(st.tileRevision).length,
  };
}, seconds);
console.log(JSON.stringify(result, null, 1));

if (argv.includes('--lava')) {
  const lava = await ev(() => {
    const w = window.__typhon.getState().world;
    const bins = { films: 0, cool: 0, warm: 0, hot: 0, total: 0, zeroT: 0 };
    for (let ty = w.tiles.minTy; ty <= w.tiles.maxTy; ty++)
      for (let tx = w.tiles.minTx; tx <= w.tiles.maxTx; tx++) {
        const d = window.__typhonTile(2, tx, ty)?.values;
        const T = window.__typhonTile(3, tx, ty)?.values;
        if (!d) continue;
        for (let k = 0; k < d.length; k++) {
          if (d[k] <= 0.02) continue;
          bins.total++;
          const t = T ? T[k] : 0;
          if (!(t > 0)) bins.zeroT++;
          if (t < 500) bins.cool++;
          else if (t < 800) bins.warm++;
          else bins.hot++;
          if (d[k] < 0.5 && t < 500) bins.films++;
        }
      }
    return bins;
  });
  console.log('lava cells', JSON.stringify(lava));
}
if (shot) {
  await page.screenshot({ path: shot });
  console.log('saved', shot);
}
if (errors.length) console.log('errors', errors.slice(0, 5));
await browser.close();
