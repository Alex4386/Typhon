// Screenshots of named views around the first volcano's vent, for visual checks of water, lava and
// clouds. Usage: node scripts/shot-views.mjs <outDir> [url] [prefix]
// Views: low (low angle across the water at the cone), top (top-down over shallow water),
// horizon (far view to the horizon). Prints PerfHud stats per view.
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const out = process.argv[2] ?? 'view-shots';
const url = process.argv[3] ?? 'http://localhost:8797/?debug&perf';
const prefix = process.argv[4] ?? '';
mkdirSync(out, { recursive: true });

const VIEWS = {
  // [east offset, north offset, height above sea (m), look-at height (m)]
  low: [-700, -500, 60, 10],
  top: [-60, -40, 700, 0],
  horizon: [-2500, -2500, 900, 0],
};

const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1 });
page.setDefaultTimeout(180000);
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
await page.goto(url, { waitUntil: 'commit' });
await page.waitForSelector('[data-hud]', { timeout: 180000 });
// close the first-visit guide through the store (a click can stall while tiles are being built)
await page.evaluate(() => window.__typhon.getState().set({ guideOpen: false }));
await page.waitForTimeout(20000);
if (process.env.SURGE === '1') {
  // a synthetic pyroclastic surge: a 1.2 km tongue running north-east from the vent, 1–6 m deep,
  // injected as PDC depth tiles (the shape the server streams), to look at the cloud renderer
  await page.evaluate(() => {
    const s = window.__typhon.getState();
    const w = s.world;
    const v = w.volcanoes[0];
    const vent = v.vents[0]?.at ?? v.chamber.center;
    const t = w.tileSize;
    const frames = [];
    for (let ty = w.tiles.minTy; ty <= w.tiles.maxTy; ty++) {
      for (let tx = w.tiles.minTx; tx <= w.tiles.maxTx; tx++) {
        const values = new Float32Array(t * t);
        let any = false;
        for (let r = 0; r < t; r++) {
          for (let c = 0; c < t; c++) {
            const x = w.origin[0] + (tx * t + c + 0.5) * w.cellSize - vent[0];
            const y = w.origin[1] + (ty * t + r + 0.5) * w.cellSize - vent[1];
            const along = (x + y) / Math.SQRT2;
            const across = (x - y) / Math.SQRT2;
            if (along < 80 || along > 1300) continue;
            const half = 90 + along * 0.12;
            if (Math.abs(across) > half) continue;
            values[r * t + c] = 1 + 5 * (1 - Math.abs(across) / half) * (along / 1300);
            any = true;
          }
        }
        if (any) frames.push({ level: 0, field: 5, codec: 0, tileX: tx, tileY: ty, version: 1e9, width: t, height: t, time: 0, values });
      }
    }
    s.applyTiles(frames);
  });
  await page.waitForTimeout(3000);
  VIEWS.surge = [-900, 300, 250, 60];
  VIEWS.surgeFar = [-3500, -2500, 1800, 0];
}
for (const [name, [de, dn, h, lookH]] of Object.entries(VIEWS)) {
  await page.evaluate(
    ({ de, dn, h, lookH }) => {
      const s = window.__typhon.getState();
      const v = s.world.volcanoes[0];
      const vent = v.vents[0]?.at ?? v.chamber.center;
      const ve = s.verticalExaggeration;
      const target = [vent[0], lookH * ve, -vent[1]];
      const position = [vent[0] + de, h * ve, -(vent[1] + dn)];
      const dx = target[0] - position[0];
      const dy = target[1] - position[1];
      const dz = target[2] - position[2];
      const heading = Math.atan2(dx, -dz);
      const pitch = Math.atan2(dy, Math.hypot(dx, dz));
      window.__typhonCamera.getState().requestCamera({ kind: 'pose', instant: true, pose: { mode: 'orbit', position, heading, pitch, target } });
    },
    { de, dn, h, lookH },
  );
  await page.waitForTimeout(6000);
  await page.screenshot({ path: `${out}/${prefix}${name}.png` });
  const perf = await page.evaluate(() => {
    const p = window.__typhonPerf;
    return p ? { calls: p.calls, triangles: p.triangles, frameMs: p.frameMs ?? p.cpuMs, cpuMs: p.cpuMs } : null;
  });
  console.log(name, JSON.stringify(perf));
}
console.log('errors:', errors.slice(0, 6).join(' | ') || 'none');
await browser.close();
