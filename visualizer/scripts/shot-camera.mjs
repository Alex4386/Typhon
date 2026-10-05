// Screenshots of every camera mode against a running sim-server (production UI with ?debug).
// Usage: node scripts/shot-camera.mjs outDir [baseUrl]
// Software WebGL is slow; waits are generous and poses are set directly through the camera store.
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const outDir = process.argv[2] ?? 'camera-shots';
const base = process.argv[3] ?? 'http://localhost:8791/';
mkdirSync(outDir, { recursive: true });

const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1 });
page.setDefaultTimeout(600000);
const logs = [];
page.on('console', (m) => (m.type() === 'error' || m.type() === 'warning') && logs.push(`${m.type()}: ${m.text()}`));
page.on('pageerror', (e) => logs.push(`pageerror: ${e.message}`));

const url = `${base}?renderer=webgl&debug&quality=low`;
await page.goto(url, { waitUntil: 'commit' });
for (let i = 0; i < 120; i++) {
  await page.waitForTimeout(2000);
  const n = await page.evaluate(() => window.__typhonMessages?.binary ?? 0).catch(() => 0);
  if (n > 60) break;
}
await page.waitForTimeout(15000);

// ONLY=06,07 limits which shots are taken (others are skipped quickly)
const only = process.env.ONLY ? process.env.ONLY.split(',') : null;
const shot = async (name, wait = 12) => {
  if (only && !only.some((p) => name.startsWith(p))) return;
  await page.waitForTimeout(wait * 1000);
  await page.screenshot({ path: `${outDir}/${name}.png`, timeout: 600000 });
  const r = await page.evaluate(() => window.__typhonCamera.getState().readout);
  console.log('saved', name, r ? `heading=${((r.heading * 180) / Math.PI).toFixed(0)} alt=${r.altitude.toFixed(0)} agl=${r.aboveGround.toFixed(0)}` : '');
};
const req = (r) => page.evaluate((r) => window.__typhonCamera.getState().requestCamera(r), r);

await shot('01-orbit-summit', 2);

await req({ kind: 'frame', what: 'overview' });
await shot('02-overview');

// free fly: low over the flank looking at the summit pit
await req({ kind: 'mode', mode: 'fly' });
await page.waitForTimeout(3000);
const vent = await page.evaluate(() => {
  const w = window.__typhon.getState().world;
  return w.volcanoes[0].vents[0].at;
});
const ground = async (x, y) =>
  page.evaluate(([x, y]) => {
    const s = window.__typhon.getState();
    const w = s.world;
    const i = Math.floor((x - w.origin[0]) / w.cellSize);
    const j = Math.floor((y - w.origin[1]) / w.cellSize);
    const t = w.tileSize;
    const tile = window.__typhonTile(1, Math.floor(i / t), Math.floor(j / t));
    const e = tile ? tile.values[(j - Math.floor(j / t) * t) * tile.width + (i - Math.floor(i / t) * t)] : 0;
    return e * s.verticalExaggeration;
  }, [x, y]);
{
  const x = vent[0] - 900;
  const y = vent[1] - 1400;
  const g = await ground(x, y);
  await req({ kind: 'pose', pose: { mode: 'fly', position: [x, g + 220, -y], heading: 0.55, pitch: -0.12 }, instant: true });
  await shot('03-fly-flank');
}

// walk: standing on the caldera rim looking into the pit
{
  const x = vent[0] - 650;
  const y = vent[1] - 120;
  const g = await ground(x, y);
  await req({ kind: 'mode', mode: 'walk' });
  await page.waitForTimeout(2000);
  await req({ kind: 'pose', pose: { mode: 'walk', position: [x, g + 3, -y], heading: Math.PI / 2 + 0.12, pitch: -0.08 }, instant: true });
  await shot('04-walk-rim');
}

// underground: fly below the surface to look at the chamber marker
{
  await page.evaluate(() => window.__typhonCamera.getState().set({ allowUnderground: true }));
  const g = await ground(vent[0], vent[1] - 2500);
  await req({ kind: 'mode', mode: 'fly' });
  await page.waitForTimeout(2000);
  await req({ kind: 'pose', pose: { mode: 'fly', position: [vent[0], g - 400, -(vent[1] - 2500)], heading: 0, pitch: -0.25 }, instant: true });
  await shot('05-fly-underground');
  await page.evaluate(() => window.__typhonCamera.getState().set({ allowUnderground: false }));
}

// follow the vent
await page.evaluate(() => window.__typhonCamera.getState().set({ followTarget: 'vent' }));
await req({ kind: 'mode', mode: 'follow' });
await page.waitForTimeout(2000);
await req({ kind: 'frame', what: 'volcano' });
await shot('06-follow-vent');

// tour
await req({ kind: 'mode', mode: 'tour' });
await shot('07-tour', 20);

// help overlay
await req({ kind: 'mode', mode: 'orbit' });
await page.evaluate(() => window.__typhonCamera.getState().set({ helpOpen: true }));
await shot('08-help', 4);
await page.evaluate(() => window.__typhonCamera.getState().set({ helpOpen: false }));

if (process.env.PLUME) {
  await req({ kind: 'frame', what: 'plume' });
  await shot('09-plume', 20);
}

console.log('url', page.url());
console.log('console problems:', logs.length ? '\n  ' + logs.slice(0, 15).join('\n  ') : 'none');
await browser.close();
