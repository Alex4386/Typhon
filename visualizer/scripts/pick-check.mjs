// Clicks each pickable entity kind at its projected screen position and reports what got selected.
// Usage: node scripts/pick-check.mjs [url] [shotDir]    (KINDS=vent,dike to pick a subset)
import { chromium } from 'playwright';

const url = process.argv[2] ?? 'http://127.0.0.1:8797/?debug';
const shotDir = process.argv[3];
const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
page.on('console', (m) => { if (m.text().startsWith('PICKDBG')) console.log('   ', m.text()); });
await page.goto(url, { waitUntil: 'commit' });
await page.waitForSelector('[data-hud]', { timeout: 120000 });
await page.evaluate(() => { window.__typhon.getState().set({ guideOpen: false }); window.__pickDebug = true; });
await page.waitForTimeout(8000);
const kinds = process.env.KINDS ? process.env.KINDS.split(',') : ['vent', 'fissure', 'dike', 'chamber', 'connection'];
let failed = 0;
for (const kind of kinds) {
  await page.evaluate(() => window.__typhon.getState().select(null));
  const target = await page.evaluate((k) => {
    const s = window.__typhon.getState();
    const e = Object.values(s.entities).find((x) => x.kind === k && x.removedAt === undefined);
    if (!e) return null;
    // frame it (deep objects too), then clear the selection so the click has to make it
    s.select({ type: 'entity', id: e.id });
    window.__typhonCamera.getState().requestCamera({ kind: 'frameSelection' });
    return { id: e.id, at: e.at, path: e.path ?? null };
  }, kind);
  await page.waitForTimeout(2500);
  await page.evaluate(() => window.__typhon.getState().select(null));
  if (!target) {
    console.log(`${kind}: none in the world`);
    continue;
  }
  await page.waitForTimeout(2500);
  const pt = await page.evaluate(({ at, path }) => {
    const s = window.__typhon.getState();
    const cam = window.__typhonCam;
    const v = s.verticalExaggeration;
    // dikes: aim at an on-screen point of the middle of the path (clear of the chamber below and the
    // fissure it opened at the surface)
    let p = at;
    if (path && path.length > 1) {
      // a two-point path (a pathway between chambers): points along it, not its ends inside the chambers
      const along = path.length === 2 ? [0.5, 0.4, 0.6, 0.3, 0.7].map((t) => path[0].map((a, i) => a + (path[1][i] - a) * t)) : path;
      const mid = path.length === 2 ? along : along.slice(Math.floor(along.length * 0.3), Math.ceil(along.length * 0.7));
      p =
        mid.find((x) => {
          const t = cam.position.clone().set(x[0], x[2] * v, -x[1]).project(cam);
          return Math.abs(t.x) < 0.9 && Math.abs(t.y) < 0.9 && t.z < 1;
        }) ?? path[Math.floor(path.length / 2)];
    }
    const q = cam.position.clone().set(p[0], p[2] * v, -p[1]).project(cam);
    const r = document.querySelector('canvas').getBoundingClientRect();
    return { x: r.left + ((q.x + 1) / 2) * r.width, y: r.top + ((1 - q.y) / 2) * r.height, onScreen: Math.abs(q.x) < 1 && Math.abs(q.y) < 1 };
  }, target);
  if (!pt.onScreen) {
    console.log(`${kind}: off screen`);
    failed++;
    continue;
  }
  await page.mouse.move(pt.x, pt.y);
  await page.waitForTimeout(400);
  const hover = await page.evaluate(() => window.__typhon.getState().hover?.label ?? null);
  await page.mouse.click(pt.x, pt.y);
  await page.waitForTimeout(600);
  const sel = await page.evaluate(() => JSON.stringify(window.__typhon.getState().selection));
  const under = await page.evaluate(({ x, y }) => {
    const el = document.elementFromPoint(x, y);
    return el ? `${el.tagName}.${String(el.className).slice(0, 50)}` : 'none';
  }, pt);
  if (!sel.includes(target.id)) console.log(`    under the pointer: ${under} at ${pt.x.toFixed(0)},${pt.y.toFixed(0)}`);
  const ok = sel.includes(target.id);
  if (!ok) failed++;
  console.log(`${kind} ${target.id}: hover=${hover} -> selected ${sel} ${ok ? 'OK' : 'FAIL'}`);
  if (shotDir) await page.screenshot({ path: `${shotDir}/pick-${kind}.png` });
}
await browser.close();
process.exit(failed ? 1 : 0);
