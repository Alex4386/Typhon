// Screenshots of the tile pyramid: the overview (context terrain around the core) and a close view of
// the first volcano's main crater (crater-detail level), plus how many pyramid tiles arrived.
// Usage: node scripts/shot-lod.mjs <url> <out-prefix> [--size 1280x800] [--settle S]
import { chromium } from 'playwright';

const argv = process.argv.slice(2);
const opt = (n, d) => {
  const i = argv.indexOf(n);
  return i >= 0 ? argv[i + 1] : d;
};
const [url, prefix] = argv;
const [vw, vh] = String(opt('--size', '1280x800')).split('x').map(Number);
const settle = Number(opt('--settle', 12)) * 1000;
const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: vw, height: vh }, deviceScaleFactor: 1 });
page.setDefaultTimeout(300000);
const problems = [];
page.on('pageerror', (e) => problems.push(e.message));
await page.goto(url, { waitUntil: 'commit' });
const ev = (fn, a) => page.evaluate(fn, a);
const stats = () =>
  ev(() => {
    const s = window.__typhon.getState();
    const lod = {};
    for (const k of Object.keys(s.lodRevision)) {
      const l = k.split(':')[0];
      lod[l] = (lod[l] ?? 0) + 1;
    }
    return { core: Object.keys(s.tileRevision).length, lod };
  });
for (let i = 0; i < Number(opt('--max-wait', 120)) / 2; i++) {
  await page.waitForTimeout(2000);
  const r = await ev(() => {
    const s = window.__typhon?.getState();
    const w = s?.world;
    if (!w) return null;
    return { have: Object.keys(s.tileRevision).length, total: (w.tiles.maxTx - w.tiles.minTx + 1) * (w.tiles.maxTy - w.tiles.minTy + 1) };
  }).catch(() => null);
  if (i % 5 === 0) console.log('waiting', JSON.stringify(r));
  if (r && r.have >= r.total) break;
}
await ev(() => window.__typhon.getState().set({ guideOpen: false }));
// what changes with distance from the summit: ash, fresh deposits (units with a time), ground
// temperature, and the elevation range per ring (relief)
console.log(
  'rings',
  JSON.stringify(
    await ev(() => {
      const s = window.__typhon.getState();
      const w = s.world;
      const vent = w.volcanoes[0].vents[0].at;
      const T = w.tileSize;
      const at = (f, i, j) => {
        const tx = Math.floor(i / T);
        const ty = Math.floor(j / T);
        const t = window.__typhonTile(f, tx, ty);
        return t ? t.values[(j - ty * T) * T + (i - tx * T)] : undefined;
      };
      const rings = [];
      for (let r = 0; r < 7000; r += 500) rings.push({ r, n: 0, ash: 0, fresh: 0, unitTypes: {}, temp: 0, lo: Infinity, hi: -Infinity });
      const n = (w.tiles.maxTx + 1) * T;
      for (let j = 0; j < n; j += 2) {
        for (let i = 0; i < n; i += 2) {
          const x = w.origin[0] + (i + 0.5) * w.cellSize;
          const y = w.origin[1] + (j + 0.5) * w.cellSize;
          const k = Math.floor(Math.hypot(x - vent[0], y - vent[1]) / 500);
          const g = rings[k];
          if (!g) continue;
          const e = at(1, i, j);
          if (e === undefined) continue;
          g.n++;
          g.ash += at(7, i, j) ?? 0;
          const u = at(10, i, j);
          const info = s.units[u];
          if (info && info.time != null) g.fresh++;
          const dt = info ? info.depositType : -1;
          g.unitTypes[dt] = (g.unitTypes[dt] ?? 0) + 1;
          g.temp += at(8, i, j) ?? 0;
          g.lo = Math.min(g.lo, e);
          g.hi = Math.max(g.hi, e);
        }
      }
      return rings.map((g) => ({ r: g.r, n: g.n, ashMm: +((g.ash / Math.max(1, g.n)) * 1000).toFixed(1), fresh: +(g.fresh / Math.max(1, g.n)).toFixed(2), types: g.unitTypes, tempC: +(g.temp / Math.max(1, g.n)).toFixed(1), elev: [Math.round(g.lo), Math.round(g.hi)] }));
    }),
  ),
);
await ev(() => window.__typhonCamera.getState().requestCamera({ kind: 'frame', what: 'overview' }));
await page.waitForTimeout(settle);
await page.screenshot({ path: `${prefix}-overview.png` });
console.log('overview', JSON.stringify(await stats()));
// the landscape from 12 km south, 3 km up: the core and the context terrain around it
await ev(() => {
  const s = window.__typhon.getState();
  const vent = s.world.volcanoes[0].vents[0];
  const v = s.verticalExaggeration;
  window.__typhonCamera.getState().requestCamera({
    kind: 'pose',
    instant: true,
    pose: { mode: 'orbit', position: [vent.at[0], (vent.z + 3000) * v, -(vent.at[1] - 12000)], heading: 0, pitch: -0.2, target: [vent.at[0], vent.z * v, -vent.at[1]] },
  });
});
await page.waitForTimeout(settle * 2);
await page.screenshot({ path: `${prefix}-landscape.png` });
console.log('landscape', JSON.stringify(await stats()));
// low, oblique view of the crater: close enough for the detail level
await ev(() => {
  const s = window.__typhon.getState();
  const vent = s.world.volcanoes[0].vents[0];
  const v = s.verticalExaggeration;
  const r = Math.max(400, vent.radius * 4);
  window.__typhonCamera.getState().requestCamera({
    kind: 'pose',
    instant: true,
    pose: { mode: 'orbit', position: [vent.at[0] + r, (vent.z + r * 0.8) * v, -vent.at[1] + r], heading: -Math.PI * 0.75, pitch: -0.55, target: [vent.at[0], vent.z * v, -vent.at[1]] },
  });
});
await page.waitForTimeout(settle * 2);
await page.screenshot({ path: `${prefix}-crater.png` });
console.log('crater', JSON.stringify(await stats()));
if (problems.length) console.log('page errors:', problems.slice(0, 5));
await browser.close();
