// Walks Build mode on an empty world in a browser: place a chamber by typing its position, add a deeper
// chamber, connect them, and screenshot each step (server on :8797 with an empty ocean world).
// Usage: node scripts/build-walkthrough.mjs outDir
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const out = process.argv[2] ?? 'build-shots';
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const logs = [];
page.on('pageerror', (e) => logs.push(`pageerror: ${e.message}`));
page.on('console', (m) => m.type() === 'error' && logs.push(`error: ${m.text()}`));
await page.goto('http://127.0.0.1:8797/?renderer=webgl&debug', { waitUntil: 'commit' });
await page.waitForSelector('[aria-label="Empty world"]', { timeout: 180000 });
await page.evaluate(() => window.__typhon.getState().set({ guideOpen: false }));
const st = () => page.evaluate(() => { const s = window.__typhon.getState(); return { draft: s.buildDraft, drawer: s.drawer, volcanoes: s.world?.volcanoes.map((v) => v.id), chambers: Object.keys(s.entities).filter((k) => k.startsWith('chamber:') || k.startsWith('connection:')) }; });

// 1. the empty-world card starts a draft in Build mode
await page.getByRole('button', { name: 'Place a magma chamber to start' }).click();
await page.waitForTimeout(800);
console.log('draft', JSON.stringify(await st()));
// 2. type its position
await page.getByLabel('East (m)').fill('150');
await page.getByLabel('North (m)').fill('-80');
await page.waitForTimeout(800);
console.log('typed', JSON.stringify((await st()).draft));
await page.screenshot({ path: `${out}/1-draft.png` });
// 3. place it
await page.getByRole('button', { name: 'Place chamber' }).click();
for (let i = 0; i < 120; i++) {
  await page.waitForTimeout(1000);
  const s = await st();
  if (s.volcanoes?.length && s.chambers.length) break;
}
console.log('placed', JSON.stringify(await st()));
// 4. a deeper chamber in the same volcano
await page.getByRole('button', { name: 'Add chamber' }).first().click();
await page.waitForTimeout(500);
await page.evaluate(() => { const s = window.__typhon.getState(); s.set({ buildDraft: { ...s.buildDraft, values: { ...s.buildDraft.values, depthM: 5000 } } }); });
await page.waitForTimeout(500);
await page.screenshot({ path: `${out}/2-second-draft.png` });
await page.getByRole('button', { name: 'Place chamber' }).click();
for (let i = 0; i < 120; i++) {
  await page.waitForTimeout(1000);
  const s = await st();
  if (s.chambers.length >= 2) break;
}
console.log('second', JSON.stringify(await st()));
// 5. connect them
await page.getByRole('button', { name: 'Connect chambers' }).first().click();
await page.waitForTimeout(500);
const state = await st();
const deep = state.chambers.find((k) => k.split(':').length === 3)?.split(':')[2];
await page.evaluate((deep) => { const s = window.__typhon.getState(); s.set({ connectDraft: { ...s.connectDraft, from: deep, to: 'main' } }); }, deep);
await page.waitForTimeout(300);
await page.getByRole('button', { name: 'Connect', exact: true }).click();
for (let i = 0; i < 120; i++) {
  await page.waitForTimeout(1000);
  const s = await st();
  if (s.chambers.some((k) => k.startsWith('connection:'))) break;
}
console.log('connected', JSON.stringify(await st()));
await page.evaluate(() => window.__typhon.getState().set({ xray: true }));
await page.waitForTimeout(2000);
await page.screenshot({ path: `${out}/3-plumbing.png` });
console.log('problems', logs.slice(0, 8).join('\n') || 'none');
await browser.close();
