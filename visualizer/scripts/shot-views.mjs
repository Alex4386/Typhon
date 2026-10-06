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
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
await page.goto(url, { waitUntil: 'commit' });
await page.waitForSelector('[data-hud]', { timeout: 180000 });
const gotIt = page.getByRole('button', { name: 'Got it' });
if (await gotIt.isVisible().catch(() => false)) await gotIt.click();
await page.waitForTimeout(20000);
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
