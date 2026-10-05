// Screenshots of the visualizer (Vite dev server, which exposes debug hooks) against a real
// sim-server (M6), e.g. one started with
//   sim-server --preset kilauea --ui visualizer/dist
// Usage: node scripts/screenshot-server.mjs [outDir] [url]
// Set CHROME=/path/to/chrome to use a specific Chromium build.
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const out = process.argv[2] ?? 'screenshots-server';
const url = process.argv[3] ?? 'http://localhost:5180/?renderer=webgl&server=ws://localhost:8787/ws';
mkdirSync(out, { recursive: true });

const browser = await chromium.launch({
  executablePath: process.env.CHROME || undefined,
  // /dev/shm-backed IPC crashed headless Chromium on long WebGL sessions in our CI-like environment
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
});
const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1 });
page.setDefaultTimeout(120000);
const logs = [];
page.on('console', (m) => (m.type() === 'error' || m.type() === 'warning') && logs.push(`${m.type()}: ${m.text()}`));
page.on('pageerror', (e) => logs.push(`pageerror: ${e.message}`));

const shot = async (name) => {
  await page.screenshot({ path: `${out}/${name}.png` });
  console.log('saved', `${out}/${name}.png`);
};
const click = (text) => page.getByRole('button', { name: text, exact: false }).first().dispatchEvent('click');

// Software-rendered headless pages are slow to respond; poll rather than relying on
// actionability/selector checks.
await page.goto(url, { waitUntil: 'commit' });
for (let i = 0; i < 60; i++) {
  await page.waitForTimeout(2000);
  const n = await page.evaluate(() => window.__typhonMessages?.binary ?? 0).catch(() => 0);
  if (n > 60) break;
}
await page.waitForTimeout(6000);
await shot('01-initial');

await click('erupt');
await click('max');
await page.waitForTimeout(15000);
await shot('02-erupting');

// cross-section through the first vent, west → east
const line = await page.evaluate(() => {
  const w = window.__typhon.getState().world;
  const v = w.volcanoes[0].vents[0].at;
  const half = Math.min(1500, (w.tiles.maxTx + 1) * w.tileSize * w.cellSize * 0.4);
  return [[v[0] - half, v[1]], [v[0] + half, v[1]]];
});
await page.evaluate((l) => window.__typhon.getState().set({ sectionPolyline: l }), line);
await click('Cut');
await page.waitForFunction(() => window.__typhon?.getState().section !== null, null, { timeout: 60000 });
await page.waitForTimeout(3000);
await shot('03-section');

for (const tab of ['magma', 'deformation', 'alerts']) {
  await click(tab);
  await page.waitForTimeout(1500);
  await shot(`04-observatory-${tab}`);
}

const counts = await page.evaluate(() => window.__typhonMessages);
console.log('messages:', JSON.stringify(counts));
console.log('console problems:', logs.length ? '\n  ' + logs.slice(0, 20).join('\n  ') : 'none');
await browser.close();
