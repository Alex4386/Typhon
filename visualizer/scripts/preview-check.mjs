// Types a new chamber volume in the Inspector and checks the derived radius previews before it is applied.
// Usage: node scripts/preview-check.mjs [url] [shot.png]
import { chromium } from 'playwright';

const url = process.argv[2] ?? 'http://127.0.0.1:8797/?debug';
const shot = process.argv[3];
const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 860 } });
await page.goto(url, { waitUntil: 'commit' });
await page.waitForSelector('[data-hud]', { timeout: 120000 });
await page.evaluate(() => window.__typhon.getState().set({ guideOpen: false }));
await page.waitForTimeout(5000);
await page.evaluate(() => {
  const s = window.__typhon.getState();
  const e = Object.values(s.entities).find((x) => x.kind === 'chamber' && x.id.split(':').length === 3) ?? s.entities[Object.keys(s.entities).find((k) => k.startsWith('chamber:'))];
  s.select({ type: 'entity', id: e.id });
});
await page.waitForTimeout(1000);
await page.getByRole('tab', { name: /^walls$/i }).first().click();
await page.waitForTimeout(500);
const box = page.locator('input[id$=".volume"]').first();
const before = await box.inputValue();
await box.fill(String(Number(before) * 8));
await page.waitForTimeout(600); // the dry run answers; the change itself waits for the slider to rest
const preview = await page.locator('dd span.text-sky-300').first().textContent().catch(() => null);
if (shot) await page.screenshot({ path: shot });
const dialog = await page.getByRole('dialog').count();
console.log(`volume box ${before} → ${Number(before) * 8}; radius preview: ${preview}; confirm dialog yet: ${dialog > 0 ? 'shown' : 'not yet'}`);
await page.waitForTimeout(1000);
console.log(`confirm dialog after the slider rests: ${(await page.getByRole('dialog').count()) > 0 ? 'shown' : 'none'}`);
await browser.close();
