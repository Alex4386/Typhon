// Minimal headless probe: loads the page, prints console output and renderer, takes one screenshot.
import { chromium } from 'playwright';

const url = process.argv[2] ?? 'http://localhost:5180/';
const out = process.argv[3] ?? 'probe.png';
const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined, args: (process.env.CHROME_ARGS ?? '').split(' ').filter(Boolean) });
const page = await browser.newPage({ viewport: { width: 1400, height: 860 } });
page.on('console', (m) => console.log(`[console.${m.type()}]`, m.text().slice(0, 300)));
page.on('pageerror', (e) => console.log('[pageerror]', e.message));
const t0 = Date.now();
await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
console.log('loaded', Date.now() - t0, 'ms');
await page.waitForTimeout(Number(process.env.WAIT ?? 6000));
console.log('fps:', await page.evaluate(() => new Promise((r) => { let n = 0; const t = performance.now(); const f = () => { n++; if (performance.now() - t < 3000) requestAnimationFrame(f); else r(n / 3); }; requestAnimationFrame(f); })));
console.log('store:', await page.evaluate(() => { const s = window.__typhon?.getState(); return s && JSON.stringify({ clock: s.clock, stateTime: s.state?.time, replay: s.replayInfo, errors: s.errors, renderer: s.renderer, tiles: Object.keys(s.tileRevision).length, events: s.events.length, status: s.status, msgs: window.__typhonMessages }); }));
console.log('header:', (await page.locator('header').textContent({ timeout: 5000 }))?.slice(0, 300));
await page.screenshot({ path: out, timeout: 60000 });
console.log('shot', out, Date.now() - t0, 'ms');
await browser.close();
