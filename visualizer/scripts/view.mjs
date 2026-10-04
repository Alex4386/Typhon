// Debug helper: screenshot with store overrides, e.g.
//   node scripts/view.mjs out.png '{"showAtmosphere":false,"colorMode":"natural"}' [waitMs]
import { chromium } from 'playwright';

const out = process.argv[2] ?? 'view.png';
const overrides = JSON.parse(process.argv[3] ?? '{}');
const wait = Number(process.argv[4] ?? 3000);
const url = process.env.URL ?? 'http://localhost:5180/?renderer=webgl';
const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
page.setDefaultTimeout(120000);
page.on('pageerror', (e) => console.log('[pageerror]', e.message));
await page.goto(url);
await page.waitForFunction(() => (window.__typhonMessages?.clock ?? 0) > 1, null, { timeout: 120000 });
await page.evaluate((o) => window.__typhon.getState().set(o), overrides);
await page.waitForTimeout(wait);
await page.screenshot({ path: out });
console.log('saved', out);
await browser.close();
