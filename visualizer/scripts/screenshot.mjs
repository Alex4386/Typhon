// Headless smoke test + screenshots of the visualizer against a running dev server and mock.
// Usage: npm install --no-save playwright && node scripts/screenshot.mjs [outDir] [url]
// Set CHROME=/path/to/chrome to use a specific Chromium build.
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const out = process.argv[2] ?? 'screenshots';
const url = process.argv[3] ?? 'http://localhost:5180/';
mkdirSync(out, { recursive: true });

const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
page.setDefaultTimeout(90000);
const logs = [];
page.on('console', (m) => (m.type() === 'error' || m.type() === 'warning') && logs.push(`${m.type()}: ${m.text()}`));
page.on('pageerror', (e) => logs.push(`pageerror: ${e.message}`));

const shot = async (name) => {
  await page.screenshot({ path: `${out}/${name}.png` });
  console.log('saved', `${out}/${name}.png`);
};
// Software-rendered headless pages draw ~1–2 fps, which makes Playwright's actionability checks
// time out; dispatch DOM clicks directly instead.
const click = (text) => page.getByRole('button', { name: text, exact: false }).first().dispatchEvent('click');

await page.goto(url);
await page.waitForSelector('canvas', { timeout: 60000 });
// software-rendered headless browsers draw ~1–2 fps; give the initial tile burst time to drain
await page.waitForFunction(() => (window.__typhonMessages?.clock ?? 0) > 2, null, { timeout: 120000 });
await page.waitForTimeout(4000);
await shot('01-initial');

// erupt the main volcano and run fast
await click('erupt');
await click('max');
await page.waitForTimeout(20000);
await shot('02-erupting');

// erupt the explosive cone too
await page.locator('header .hdr-volcano').nth(1).dispatchEvent('click');
await click('erupt');
await page.waitForTimeout(15000);
await shot('03-explosive');

// cross-section across both volcanoes
const canvas = page.locator('.view canvas').first();
const box = await canvas.boundingBox();
await click('Draw line');
await page.mouse.click(box.x + box.width * 0.3, box.y + box.height * 0.55);
await page.waitForTimeout(1500);
await page.mouse.click(box.x + box.width * 0.75, box.y + box.height * 0.6);
await page.waitForTimeout(1500);
const picked = await page.evaluate(() => window.__typhon.getState().sectionPolyline.length);
console.log('points picked on the map:', picked);
if (picked < 2) {
  // fall back to a line through both volcanoes
  await page.evaluate(() => window.__typhon.getState().set({ sectionPolyline: [[-4000, 1500], [4500, -3000]] }));
}
await click('Cut');
await page.waitForFunction(() => window.__typhon?.getState().section !== null, null, { timeout: 60000 });
await page.waitForTimeout(3000);
await shot('04-section');

// observatory tabs + ground temperature colouring
await click('magma');
await page.evaluate(() => window.__typhon.getState().set({ colorMode: 'surfaceTemperature' }));
await page.waitForTimeout(8000);
await shot('05-magma-thermal');
await click('deformation');
await page.evaluate(() => window.__typhon.getState().set({ colorMode: 'topUnit' }));
await page.waitForTimeout(8000);
await shot('06-deformation-units');

const renderer = await page.locator('header .muted').last().textContent();
console.log('renderer:', renderer);
console.log(logs.length ? logs.slice(0, 20).join('\n') : 'no console errors');
await browser.close();
