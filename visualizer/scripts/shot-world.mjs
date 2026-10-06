// Screenshots of a running world once all its tiles have arrived, framed by the camera presets.
// Usage: node scripts/shot-world.mjs <url> <out-prefix> [--frames overview,volcano] [--size 1440x900]
//          [--erupt] (start an eruption first and wait --erupt-wait seconds) [--settle S]
// Writes <out-prefix>-<frame>.png for each frame.
import { chromium } from 'playwright';

const argv = process.argv.slice(2);
const opt = (n, d) => {
  const i = argv.indexOf(n);
  return i >= 0 ? argv[i + 1] : d;
};
const url = argv[0];
const prefix = argv[1];
const frames = String(opt('--frames', 'overview,volcano')).split(',');
const [vw, vh] = String(opt('--size', '1440x900')).split('x').map(Number);
const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: vw, height: vh }, deviceScaleFactor: 1 });
page.setDefaultTimeout(900000);
const problems = [];
page.on('pageerror', (e) => problems.push(e.message));
await page.goto(url, { waitUntil: 'commit' });
const ev = (fn, a) => page.evaluate(fn, a);
const maxWait = Number(opt('--max-wait', 240));
for (let i = 0; i < maxWait / 2; i++) {
  await page.waitForTimeout(2000);
  const r = await ev(() => {
    const s = window.__typhon?.getState();
    const w = s?.world;
    if (!w) return null;
    const total = (w.tiles.maxTx - w.tiles.minTx + 1) * (w.tiles.maxTy - w.tiles.minTy + 1);
    return { have: Object.keys(s.tileRevision).length, total };
  }).catch(() => null);
  if (r && r.have >= r.total) break;
}
await ev(() => window.__typhon.getState().set({ guideOpen: false }));
if (argv.includes('--erupt')) {
  await ev(() => {
    const s = window.__typhon.getState();
    const v = s.world.volcanoes[0].id;
    window.__typhonCommand?.({ kind: 'startEruption', volcanoId: v });
  });
  await page.waitForTimeout(Number(opt('--erupt-wait', 30)) * 1000);
}
for (const what of frames) {
  await ev((w) => window.__typhonCamera.getState().requestCamera({ kind: 'frame', what: w }), what);
  await page.waitForTimeout(Number(opt('--settle', 12)) * 1000);
  const out = `${prefix}-${what}.png`;
  await page.screenshot({ path: out, timeout: 900000 });
  console.log('saved', out);
}
if (problems.length) console.log('page errors:', problems.slice(0, 5));
await browser.close();
