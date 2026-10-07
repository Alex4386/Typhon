// Selects objects of each kind and screenshots the Inspector (desktop, and a phone with MOBILE=1).
// Usage: node scripts/inspector-shots.mjs [url] [shotDir]
// Each step: SELECT=<entity id prefix>[@tab], e.g. "chamber:@supply" — defaults cover chamber tabs,
// a dike, a further chamber, the volcano, the world (a ground point) and the Settings drawer.
import { chromium } from 'playwright';

const url = process.argv[2] ?? 'http://127.0.0.1:8797/?debug';
const shotDir = process.argv[3] ?? '.';
const mobile = process.env.MOBILE === '1';
const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage(mobile ? { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true } : { viewport: { width: 1280, height: 860 } });
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
await page.goto(url, { waitUntil: 'commit' });
await page.waitForSelector('[data-hud]', { timeout: 120000 });
await page.evaluate(() => window.__typhon.getState().set({ guideOpen: false }));
await page.waitForTimeout(6000);

const steps = (process.env.STEPS ?? 'chamber:@overview,chamber:@supply,chamber:@walls,chamber:@overrides,dike:,chamber:*:,volcano:,vent:,point,settings').split(',');
for (const step of steps) {
  const [what, tab] = step.split('@');
  const picked = await page.evaluate((w) => {
    const s = window.__typhon.getState();
    s.set({ drawer: null });
    if (w === 'point') {
      const v = Object.values(s.entities).find((e) => e.kind === 'vent');
      s.select({ type: 'point', at: v ? [v.at[0] + 400, v.at[1] + 300] : [0, 0] });
      return 'point';
    }
    if (w === 'settings') {
      s.select(null);
      s.set({ drawer: 'tune' });
      return 'settings';
    }
    const extra = w === 'chamber:*:';
    const e = Object.values(s.entities).find((x) =>
      extra ? x.kind === 'chamber' && x.id.split(':').length === 3 : x.id.startsWith(w) && (w !== 'chamber:' || x.id.split(':').length === 2),
    );
    if (!e) return null;
    s.select({ type: 'entity', id: e.id });
    return e.id;
  }, what);
  if (!picked) {
    console.log(`${step}: nothing to select`);
    continue;
  }
  await page.waitForTimeout(1500);
  if (tab) {
    const t = page.getByRole('tab', { name: new RegExp(`^${tab}$`, 'i') });
    if (await t.count()) await t.first().click();
    else console.log(`${step}: no tab ${tab}`);
    await page.waitForTimeout(500);
  }
  const tabs = await page.evaluate(() => [...document.querySelectorAll('[aria-label="Inspector sections"] [role="tab"]')].map((t) => t.textContent));
  const file = `${shotDir}/inspector-${mobile ? 'm-' : ''}${step.replace(/[^a-z0-9]+/gi, '_')}.png`;
  await page.screenshot({ path: file });
  console.log(`${step}: ${picked} tabs=[${tabs.join(', ')}] → ${file}`);
}
if (errors.length) console.log('page errors:', errors);
await browser.close();
