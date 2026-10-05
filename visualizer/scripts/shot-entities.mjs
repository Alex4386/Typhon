// Entity lifecycle and inspector screenshots: default view, the Entities panel, a selected entity,
// a forced dike, a picked ground point, the command palette.
// Usage: node scripts/shot-entities.mjs outDir [url] [--dike]
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const args = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const forceDike = process.argv.includes('--dike');
const out = args[0] ?? 'entity-shots';
const url = args[1] ?? 'http://localhost:8790/?renderer=webgl&debug&quality=low';
mkdirSync(out, { recursive: true });

const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
page.setDefaultTimeout(300000);
const logs = [];
page.on('console', (m) => m.type() === 'error' && logs.push(`error: ${m.text()}`));
page.on('pageerror', (e) => logs.push(`pageerror: ${e.message}`));
await page.goto(url, { waitUntil: 'commit' });

const store = (fn, arg) => page.evaluate(fn, arg);
const shot = async (name) => {
  await page.waitForTimeout(2500); // software WebGL draws about one frame a second
  await page.screenshot({ path: `${out}/${name}.png` });
  console.log('saved', `${out}/${name}.png`);
};

// wait for the world and its first tiles
for (let i = 0; i < 180; i++) {
  await page.waitForTimeout(1000);
  const b = await store(() => window.__typhonMessages?.binary ?? 0).catch(() => 0);
  if (b > 40) break;
}
await store(() => window.__typhon.getState().set({ guideOpen: false }));
await page.waitForTimeout(4000);
await shot('01-default');

const counts = await store(() => {
  const by = {};
  for (const e of Object.values(window.__typhon.getState().entities)) by[e.kind] = (by[e.kind] ?? 0) + 1;
  return by;
});
console.log('entities by kind', JSON.stringify(counts));

await store(() => window.__typhon.getState().set({ drawer: 'entities' }));
await shot('02-entities-panel');

// select the most interesting entity (a feature if any, else a vent) through the panel
const target = await store(() => {
  const all = Object.values(window.__typhon.getState().entities).filter((e) => !e.hidden);
  return (all.find((e) => e.kind === 'feature' && ['HOT_SPRING', 'GEYSER', 'FUMAROLE', 'MUD_POT', 'SULFUR_SPRING'].includes(e.props.feature)) ?? all.find((e) => e.kind === 'vent') ?? all[0])?.label ?? null;
});
if (target) {
  await page.locator('[role=option] button', { hasText: target }).first().click();
  await page.waitForTimeout(6000);
  await shot('03-entity-selected');
}

if (forceDike) {
  await page.getByRole('button', { name: 'Tools' }).click();
  await page.getByRole('menuitem', { name: /Push magma up/ }).click();
  let dike = null;
  for (let i = 0; i < 90 && !dike; i++) {
    await page.waitForTimeout(1000);
    dike = await store(() => Object.values(window.__typhon.getState().entities).find((e) => e.kind === 'dike')?.id ?? null);
  }
  console.log('dike entity', dike);
  if (dike) {
    await store((id) => {
      window.__typhon.getState().select({ type: 'entity', id });
      window.__typhonCamera.getState().requestCamera({ kind: 'frameSelection' });
    }, dike);
    await page.waitForTimeout(8000);
    await shot('04-dike-selected');
    // follow it for a while: it propagates, then stalls or erupts
    await page.waitForTimeout(20000);
    await shot('05-dike-later');
  }
}

// click the ground in the middle of the view: point inspection
await store(() => window.__typhon.getState().select(null));
const box = await page.locator('canvas').first().boundingBox();
await page.mouse.click(box.x + box.width * 0.45, box.y + box.height * 0.6);
await page.waitForTimeout(8000);
const sel = await store(() => window.__typhon.getState().selection);
console.log('clicked selection', JSON.stringify(sel)?.slice(0, 200));
await shot('06-ground-picked');

await page.keyboard.press('Control+k');
await page.keyboard.type(process.env.PALETTE_QUERY ?? 'vent');
await shot('07-command-palette');
await page.keyboard.press('Escape');

console.log(logs.slice(0, 20).join('\n'));
await browser.close();
