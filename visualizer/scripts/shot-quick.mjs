// One screenshot of a running visualizer once its tiles have streamed in.
// Usage: node scripts/shot-quick.mjs out.png [url] [waitSeconds] [file-with-js-to-evaluate-before-the-wait]
import { readFileSync } from 'node:fs';
import { chromium } from 'playwright';

const out = process.argv[2] ?? 'shot.png';
const url = process.argv[3] ?? 'http://localhost:8787/?renderer=webgl&debug';
const wait = Number(process.argv[4] ?? 20);
const before = process.argv[5] ? readFileSync(process.argv[5], 'utf8') : undefined;

const browser = await chromium.launch({
  executablePath: process.env.CHROME || undefined,
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
});
const page = await browser.newPage({ viewport: { width: Number(process.env.W ?? 1280), height: Number(process.env.H ?? 800) }, deviceScaleFactor: 1 });
page.setDefaultTimeout(300000);
const logs = [];
page.on('console', (m) => (m.type() === 'error' || m.type() === 'warning') && logs.push(`${m.type()}: ${m.text()}`));
page.on('pageerror', (e) => logs.push(`pageerror: ${e.message}`));
await page.goto(url, { waitUntil: 'commit' });
for (let i = 0; i < 90; i++) {
  await page.waitForTimeout(2000);
  const n = await page.evaluate(() => window.__typhonMessages?.binary ?? 0).catch(() => 0);
  if (n > 60) break;
}
if (before) await page.evaluate(before).catch((e) => logs.push(`eval: ${e.message}`));
await page.waitForTimeout(wait * 1000);
await page.screenshot({ path: out, timeout: 300000 });
console.log('saved', out);
console.log('console problems:', logs.length ? '\n  ' + logs.slice(0, 12).join('\n  ') : 'none');
await browser.close();
