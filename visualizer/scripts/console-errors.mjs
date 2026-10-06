// Debug helper: loads the visualizer and prints page errors and console errors/warnings.
// Usage: node scripts/console-errors.mjs [url] [seconds]
import { chromium } from 'playwright';

const url = process.argv[2] ?? 'http://localhost:8790/?renderer=webgl&debug';
const seconds = Number(process.argv[3] ?? 15);
const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on('pageerror', (e) => console.log('pageerror', e.message, e.stack?.split('\n').slice(0, 4).join(' | ')));
page.on('console', (m) => {
  if (m.type() === 'error' || m.type() === 'warning') console.log(m.type(), m.text().slice(0, 400));
});
await page.goto(url, { waitUntil: 'commit' });
await page.waitForTimeout(seconds * 1000);
await browser.close();
