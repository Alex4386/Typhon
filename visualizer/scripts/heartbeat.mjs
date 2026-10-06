// Debug helper: loads the visualizer and prints, every 2 s, frames rendered, tiles, entities and
// how long a trivial evaluate takes (main-thread responsiveness).
// Usage: node scripts/heartbeat.mjs [url] [seconds]
import { chromium } from 'playwright';

const url = process.argv[2] ?? 'http://localhost:8790/?renderer=webgl&debug';
const seconds = Number(process.argv[3] ?? 30);
const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on('pageerror', (e) => console.log('pageerror', e.message));
await page.goto(url, { waitUntil: 'commit' });
const t0 = Date.now();
while (Date.now() - t0 < seconds * 1000) {
  await page.waitForTimeout(2000);
  const a = Date.now();
  const r = await page
    .evaluate(() => ({
      frames: window.__typhonPerf?.frames,
      fps: window.__typhonPerf?.fps,
      cpu: window.__typhonPerf?.cpuMs,
      calls: window.__typhonPerf?.calls,
      tiles: Object.keys(window.__typhon?.getState().tileRevision ?? {}).length,
      entities: Object.keys(window.__typhon?.getState().entities ?? {}).length,
      bin: window.__typhonMessages?.binary,
    }))
    .catch((e) => String(e));
  console.log(((Date.now() - t0) / 1000).toFixed(0), 'eval', Date.now() - a, 'ms', JSON.stringify(r));
}
await browser.close();
