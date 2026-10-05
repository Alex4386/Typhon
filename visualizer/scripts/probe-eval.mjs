// Evaluates a JS expression (read from a file) in a running visualizer and prints the result.
// Usage: node scripts/probe-eval.mjs <url> <file-with-expression>
import { readFileSync } from 'node:fs';
import { chromium } from 'playwright';

const [url, file] = process.argv.slice(2);
const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 800, height: 600 } });
page.setDefaultTimeout(300000);
await page.goto(url, { waitUntil: 'commit' });
for (let i = 0; i < 60; i++) {
  await page.waitForTimeout(2000);
  const ok = await page.evaluate(() => (window.__typhon?.getState().events.length ?? 0) > 20).catch(() => false);
  if (ok) break;
}
console.log(await page.evaluate(readFileSync(file, 'utf8')));
await browser.close();
