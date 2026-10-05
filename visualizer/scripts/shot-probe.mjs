// Debug helper: loads the app, logs message counters and how long the page takes to respond,
// then takes one screenshot. Usage: node scripts/shot-probe.mjs <url> <out.png>
import { chromium } from 'playwright';

const browser = await chromium.launch({
  executablePath: process.env.CHROME || undefined,
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
});
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const logs = [];
page.on('console', (m) => m.type() === 'error' && logs.push(m.text()));
page.on('pageerror', (e) => logs.push('pageerror ' + e.message));
await page.goto(process.argv[2], { waitUntil: 'commit' });
const t0 = Date.now();
for (let i = 0; i < Number(process.env.POLLS ?? 15); i++) {
  await page.waitForTimeout(2000);
  const t = Date.now();
  const n = await page.evaluate(() => JSON.stringify(window.__typhonMessages));
  console.log(i, 'eval ms', Date.now() - t, n);
}
const t = Date.now();
await page.screenshot({ path: process.argv[3], timeout: 180000 });
console.log('shot ms', Date.now() - t, 'total', Date.now() - t0);
console.log(logs.slice(0, 10).join('\n'));
await browser.close();
